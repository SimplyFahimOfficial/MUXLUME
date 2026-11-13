package com.muxlume.app.recording

import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.core.net.LimitedInputStream
import com.muxlume.app.core.net.Urls
import com.muxlume.app.core.net.request
import com.muxlume.app.recording.RecordingException.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Copies a live stream to a file, byte for byte, until its end time: a direct TS or progressive stream as it
 * arrives, an HLS stream by polling its media playlist and appending new segments in order. No re-encoding.
 * Refuses what it cannot copy faithfully (see [Reason]). Stream URLs stay in here: a failure carries only a
 * reason, never the exception that mentioned the URL.
 *
 * Network errors end the recording and keep what was written (no reconnect in v1).
 */
class Recorder(
    private val http: Call.Factory,
    private val now: () -> Long = System::currentTimeMillis,
    private val minPollMs: Long = MIN_POLL_MS,
) {
    @Volatile var bytes = 0L
        private set

    /** A write to the file failed: kept apart from network errors, which are IOExceptions too. */
    private class SinkFailure(cause: IOException) : IOException(cause)

    private class HlsState(var lastSequence: Long = -1, var failures: Int = 0)

    /** What every request and write of one recording shares. */
    private class Run(val headers: Map<String, String>, val policy: HttpPolicy, val endAtMs: Long, val sink: OutputStream)

    private inner class CountingSink(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            try {
                out.write(b, off, len)
            } catch (e: IOException) {
                throw SinkFailure(e)
            }
            bytes += len
        }
        override fun flush() = try { out.flush() } catch (e: IOException) { throw SinkFailure(e) }
    }

    /** Throws [RecordingException] for what cannot be recorded or went wrong; cancelling stops it normally. */
    suspend fun record(url: String, headers: Map<String, String>, policy: HttpPolicy, endAtMs: Long, out: OutputStream) {
        if (url.startsWith("rtsp", ignoreCase = true)) refuse(Reason.RTSP)
        val first = Urls.parseHttp(url) ?: refuse(Reason.GONE)
        if (first.encodedPath.lowercase().endsWith(".mpd")) refuse(Reason.DASH)
        try {
            withContext(Dispatchers.IO) { copy(first, headers, policy, endAtMs, CountingSink(out)) }
        } catch (e: SinkFailure) {
            val full = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space", ignoreCase = true) == true }
            refuse(if (full) Reason.STORAGE_FULL else Reason.WRITE_FAILED)
        }
    }

    private suspend fun copy(url: HttpUrl, headers: Map<String, String>, policy: HttpPolicy, endAtMs: Long, sink: OutputStream) {
        val call = http.newCall(get(url, headers, policy))
        call.cancelWithCoroutine {
            call.run().use { r ->
                if (!r.isSuccessful || r.body.contentType()?.subtype?.contains("dash+xml") == true) refuse(if (r.isSuccessful) Reason.DASH else Reason.NETWORK)
                val head = peek(r)
                when {
                    head.startsWith("#EXTM3U") -> recordHls(r, headers, policy, endAtMs, sink)
                    head.startsWith("<?xml") || head.startsWith("<MPD") -> refuse(Reason.DASH)
                    else -> recordDirect(call, r, endAtMs, sink)
                }
            }
        }
    }

    private fun peek(response: Response): String = try {
        response.body.source().peek().also { it.request(HEAD_BYTES) }.buffer.readUtf8()
    } catch (ignored: IOException) { // its message may name the URL
        refuse(Reason.NETWORK)
    }

    /** The stream ends when the server closes it (done) or at [endAtMs] (the call is cancelled then). */
    private suspend fun recordDirect(call: Call, response: Response, endAtMs: Long, sink: OutputStream) {
        val deadline = CoroutineScope(coroutineContext).launch { delay((endAtMs - now()).coerceAtLeast(0)); call.cancel() }
        try {
            val buffer = ByteArray(COPY_BYTES)
            val input = response.body.byteStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return
                sink.write(buffer, 0, n)
            }
        } catch (e: SinkFailure) {
            throw e
        } catch (ignored: IOException) {
            coroutineContext.ensureActive()
            if (!deadline.isCompleted) refuse(Reason.NETWORK) // otherwise our own deadline cancelled the call
        } finally {
            deadline.cancel()
        }
    }

    private suspend fun recordHls(first: Response, headers: Map<String, String>, policy: HttpPolicy, endAtMs: Long, sink: OutputStream) {
        var (base, media) = openHls(first, headers, policy)
        val run = Run(headers, policy, endAtMs, sink)
        val state = HlsState()
        while (true) {
            media.problem?.let { refuse(it) }
            if (!appendNew(media, base, state, run) || media.ended) return
            media = nextPlaylist(base, media.targetDurationSeconds, state, run) ?: return
        }
    }

    /** The media playlist to record and where it lives (a master playlist is resolved to its best variant). */
    private suspend fun openHls(first: Response, headers: Map<String, String>, policy: HttpPolicy): Pair<HttpUrl, HlsPlaylist.Media> {
        val parsed = HlsParser.parse(readText(first)) ?: refuse(Reason.GONE)
        if (parsed is HlsPlaylist.Media) return first.request.url to parsed
        val best = (parsed as HlsPlaylist.Master).variants.maxByOrNull { it.bandwidth } ?: refuse(Reason.GONE)
        val url = first.request.url.resolve(best.uri) ?: refuse(Reason.GONE)
        return url to fetchMedia(url, headers, policy)
    }

    /** Appends the segments not written yet, each once and in order; false once the end time has passed. */
    private suspend fun appendNew(media: HlsPlaylist.Media, base: HttpUrl, state: HlsState, run: Run): Boolean {
        var sequence = media.firstSequence
        for (uri in media.segments) {
            if (sequence > state.lastSequence) {
                if (downloadSegment(base.resolve(uri), run.headers, run.policy, run.sink)) state.failures = 0 else if (++state.failures >= MAX_FAILURES) refuse(Reason.NETWORK)
                state.lastSequence = sequence
            }
            sequence++
            if (now() >= run.endAtMs) return false
        }
        return true
    }

    /** Waits for the next playlist and fetches it; null once the end time has passed. Gives up after repeated failures. */
    private suspend fun nextPlaylist(base: HttpUrl, targetSeconds: Int, state: HlsState, run: Run): HlsPlaylist.Media? {
        while (true) {
            delay(minOf(maxOf(minPollMs, targetSeconds * HALF_SECOND_MS), (run.endAtMs - now()).coerceAtLeast(0)))
            if (now() >= run.endAtMs) return null
            try {
                return fetchMedia(base, run.headers, run.policy).also { state.failures = 0 }
            } catch (e: RecordingException) {
                if (e.reason != Reason.NETWORK || ++state.failures >= MAX_FAILURES) throw e
            }
        }
    }

    private suspend fun fetchMedia(url: HttpUrl, headers: Map<String, String>, policy: HttpPolicy): HlsPlaylist.Media {
        val call = http.newCall(get(url, headers, policy))
        return call.cancelWithCoroutine {
            call.run().use { r ->
                if (!r.isSuccessful) refuse(Reason.NETWORK)
                HlsParser.parse(readText(r)) as? HlsPlaylist.Media ?: refuse(Reason.GONE)
            }
        }
    }

    /** False if the segment could not be fetched (skipped); write failures propagate. */
    private suspend fun downloadSegment(url: HttpUrl?, headers: Map<String, String>, policy: HttpPolicy, sink: OutputStream): Boolean {
        url ?: return false
        val call = http.newCall(get(url, headers, policy))
        return call.cancelWithCoroutine {
            try {
                call.execute().use { r ->
                    if (r.isSuccessful) r.body.byteStream().copyTo(sink, COPY_BYTES)
                    r.isSuccessful
                }
            } catch (e: SinkFailure) {
                throw e
            } catch (ignored: IOException) {
                coroutineContext.ensureActive()
                false
            }
        }
    }

    /** Runs [block], cancelling the blocked [Call] if the coroutine is cancelled (OkHttp's blocking calls ignore coroutine cancellation). */
    private suspend fun <T> Call.cancelWithCoroutine(block: suspend () -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancel() // harmless once the call is done
                throw e
            }
        }
        try { block() } finally { watcher.cancel() }
    }

    private suspend fun Call.run(): Response = try {
        execute()
    } catch (ignored: IOException) { // its message may name the URL
        coroutineContext.ensureActive()
        refuse(Reason.NETWORK)
    }

    private fun get(url: HttpUrl, headers: Map<String, String>, policy: HttpPolicy): Request =
        request(url, policy).newBuilder().apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    private fun readText(response: Response): String = try {
        LimitedInputStream(response.body.byteStream(), MAX_PLAYLIST_BYTES).readBytes().decodeToString()
    } catch (ignored: IOException) { // its message may name the URL
        refuse(Reason.NETWORK)
    }

    private companion object {
        const val MIN_POLL_MS = 1_000L
        const val HALF_SECOND_MS = 500L
        const val MAX_FAILURES = 3
        const val COPY_BYTES = 64 * 1024
        const val HEAD_BYTES = 64L
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
    }
}
