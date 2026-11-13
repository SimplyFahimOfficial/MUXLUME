package com.muxlume.app.recording

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.recording.RecordingException.Reason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The recorder against a local server: no real stream, no re-encoding, only bytes in order. */
class RecorderTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).toString()
    private fun recorder() = Recorder(client, minPollMs = 10)
    private fun soon(ms: Long = 20_000) = System.currentTimeMillis() + ms

    private fun segment(n: Int) = ByteArray(4_000) { (n * 31 + it).toByte() }
    private fun bytesOf(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun serve(vararg routes: Pair<String, (Int) -> MockResponse>) {
        val hits = routes.associate { it.first to AtomicInteger() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val route = routes.firstOrNull { it.first == request.url.encodedPath } ?: return MockResponse.Builder().code(404).build()
                return route.second(hits.getValue(route.first).getAndIncrement())
            }
        }
    }

    private fun ok(body: ByteArray, type: String = "video/mp2t") = MockResponse.Builder().addHeader("Content-Type", type).body(Buffer().write(body)).build()
    private fun text(body: String, type: String = "application/vnd.apple.mpegurl") = ok(body.toByteArray(), type)

    private fun playlist(first: Int, vararg segments: Int, ended: Boolean = false, extra: String = "") =
        "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$first\n$extra" + segments.joinToString("") { "#EXTINF:1,\ns$it.ts\n" } + if (ended) "#EXT-X-ENDLIST\n" else ""

    private fun record(path: String, out: OutputStream, endAt: Long = soon(), headers: Map<String, String> = emptyMap(), recorder: Recorder = recorder()) =
        runBlocking { recorder.record(url(path), headers, HttpPolicy.ALLOW_HTTP, endAt, out) }

    private fun reasonOf(block: () -> Unit) = assertThrows(RecordingException::class.java, block).reason

    // ---- Direct streams

    @Test
    fun `a direct stream is copied byte for byte until the server ends it`() {
        val body = ByteArray(300_000) { (it * 7).toByte() }
        serve("/live.ts" to { ok(body) })
        val out = ByteArrayOutputStream()
        val r = recorder()
        runBlocking { r.record(url("/live.ts"), emptyMap(), HttpPolicy.ALLOW_HTTP, soon(), out) }
        assertThat(out.toByteArray()).isEqualTo(body)
        assertThat(r.bytes).isEqualTo(body.size.toLong())
    }

    @Test
    fun `a stream that never ends is cut at the end time and what arrived is kept`() {
        val body = ByteArray(200_000) { 1 }
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).throttleBody(2_000, 50, TimeUnit.MILLISECONDS).build())
        val out = ByteArrayOutputStream()
        val r = recorder()
        val started = System.currentTimeMillis()
        runBlocking { r.record(url("/slow.ts"), emptyMap(), HttpPolicy.ALLOW_HTTP, started + 600, out) }
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000)
        assertThat(out.size()).isGreaterThan(0)
        assertThat(out.size()).isLessThan(body.size)
        assertThat(r.bytes).isEqualTo(out.size().toLong())
    }

    @Test
    fun `a stream cut off in the middle fails with a network error and keeps the partial file`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(80_000) { 2 })).onResponseBody(SocketEffect.CloseSocket()).build())
        val out = ByteArrayOutputStream()
        val r = recorder()
        val e = assertThrows(RecordingException::class.java) { runBlocking { r.record(url("/cut.ts"), emptyMap(), HttpPolicy.ALLOW_HTTP, soon(), out) } }
        assertThat(e.reason).isEqualTo(Reason.NETWORK)
        assertThat(e.message).doesNotContain("localhost") // a failure says why, never where
        assertThat(r.bytes).isEqualTo(out.size().toLong())
    }

    @Test
    fun `playlist headers are sent`() {
        serve("/h.ts" to { ok(ByteArray(10)) })
        record("/h.ts", ByteArrayOutputStream(), headers = mapOf("User-Agent" to "Tester/1", "Referer" to "https://ref.example/"))
        val request = server.takeRequest()
        assertThat(request.headers["User-Agent"]).isEqualTo("Tester/1")
        assertThat(request.headers["Referer"]).isEqualTo("https://ref.example/")
    }

    // ---- HLS

    @Test
    fun `HLS segments are appended once each, in order, across a sliding playlist`() {
        val plays = listOf(playlist(0, 0, 1), playlist(1, 1, 2, 3), playlist(3, 3, 4, ended = true))
        serve(
            "/live.m3u8" to { n -> text(plays[minOf(n, plays.lastIndex)]) },
            *(0..4).map { i -> "/s$i.ts" to { _: Int -> ok(segment(i)) } }.toTypedArray(),
        )
        val out = ByteArrayOutputStream()
        record("/live.m3u8", out)
        assertThat(out.toByteArray()).isEqualTo(bytesOf(*(0..4).map(::segment).toTypedArray()))
    }

    @Test
    fun `a master playlist records its best variant only`() {
        serve(
            "/master.m3u8" to { text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nhigh/index.m3u8\n") },
            "/low.m3u8" to { text(playlist(0, 9, ended = true)) },
            "/high/index.m3u8" to { text(playlist(0, 0, ended = true)) },
            "/high/s0.ts" to { ok(segment(0)) },
        )
        val out = ByteArrayOutputStream()
        record("/master.m3u8", out)
        assertThat(out.toByteArray()).isEqualTo(segment(0))
        assertThat(server.requestCount).isEqualTo(3) // master, the high playlist, its one segment
    }

    @Test
    fun `a live HLS stream stops at the end time`() {
        serve("/live.m3u8" to { text(playlist(0, 0)) }, "/s0.ts" to { ok(segment(0)) })
        val out = ByteArrayOutputStream()
        val started = System.currentTimeMillis()
        record("/live.m3u8", out, endAt = started + 900)
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000)
        assertThat(out.toByteArray()).isEqualTo(segment(0)) // polled again and again, written once
    }

    @Test
    fun `a playlist that keeps failing ends the recording with a network error and keeps what was written`() {
        serve("/live.m3u8" to { n -> if (n == 0) text(playlist(0, 0)) else MockResponse.Builder().code(404).build() }, "/s0.ts" to { ok(segment(0)) })
        val out = ByteArrayOutputStream()
        assertThat(reasonOf { record("/live.m3u8", out) }).isEqualTo(Reason.NETWORK)
        assertThat(out.toByteArray()).isEqualTo(segment(0))
    }

    @Test
    fun `a segment that cannot be fetched is skipped, not fatal`() {
        val plays = listOf(playlist(0, 0, 1, 2, ended = true))
        serve(
            "/live.m3u8" to { text(plays[0]) }, "/s0.ts" to { ok(segment(0)) },
            "/s1.ts" to { MockResponse.Builder().code(500).build() }, "/s2.ts" to { ok(segment(2)) },
        )
        val out = ByteArrayOutputStream()
        record("/live.m3u8", out)
        assertThat(out.toByteArray()).isEqualTo(bytesOf(segment(0), segment(2)))
    }

    // ---- Refusals: nothing is written

    private fun refused(path: String, expected: Reason) {
        val out = ByteArrayOutputStream()
        assertThat(reasonOf { record(path, out) }).isEqualTo(expected)
        assertThat(out.size()).isEqualTo(0)
    }

    @Test
    fun `DASH, RTSP, DRM, encrypted and fragmented MP4 streams are refused`() {
        serve(
            "/manifest.mpd" to { text("<MPD/>", "application/dash+xml") },
            "/typed" to { text("anything", "application/dash+xml") },
            "/sniffed" to { text("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>", "text/plain") },
            "/aes.m3u8" to { text(playlist(0, 0, extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/k\"\n")) },
            "/drm.m3u8" to { text(playlist(0, 0, extra = "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n")) },
            "/map.m3u8" to { text(playlist(0, 0, extra = "#EXT-X-MAP:URI=\"init.mp4\"\n")) },
            "/cmaf.m3u8" to { text("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nchunk0.m4s\n") },
        )
        refused("/manifest.mpd", Reason.DASH)
        refused("/typed", Reason.DASH)
        refused("/sniffed", Reason.DASH)
        refused("/aes.m3u8", Reason.ENCRYPTED)
        refused("/drm.m3u8", Reason.DRM)
        refused("/map.m3u8", Reason.FMP4)
        refused("/cmaf.m3u8", Reason.FMP4)
        assertThat(reasonOf { runBlocking { recorder().record("rtsp://host.example/stream", emptyMap(), HttpPolicy.ALLOW_HTTP, soon(), ByteArrayOutputStream()) } }).isEqualTo(Reason.RTSP)
        assertThat(reasonOf { runBlocking { recorder().record("file:///etc/passwd", emptyMap(), HttpPolicy.ALLOW_HTTP, soon(), ByteArrayOutputStream()) } }).isEqualTo(Reason.GONE)
    }

    // ---- The file

    private class FullSink(private val room: Int, private val message: String) : OutputStream() {
        val data = ByteArrayOutputStream()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (data.size() + len > room) throw IOException(message)
            data.write(b, off, len)
        }
    }

    @Test
    fun `running out of space stops the recording and keeps what was written`() {
        serve("/live.ts" to { ok(ByteArray(100_000) { 3 }) })
        val sink = FullSink(30_000, "write failed: ENOSPC (No space left on device)")
        assertThat(reasonOf { record("/live.ts", sink) }).isEqualTo(Reason.STORAGE_FULL)
        assertThat(sink.data.size()).isGreaterThan(0)
    }

    @Test
    fun `any other write error is reported as one`() {
        serve("/live.ts" to { ok(ByteArray(100_000) { 3 }) })
        assertThat(reasonOf { record("/live.ts", FullSink(30_000, "permission revoked")) }).isEqualTo(Reason.WRITE_FAILED)
    }

    @Test
    fun `cancelling stops a recording at once`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(500_000))).throttleBody(1_000, 100, TimeUnit.MILLISECONDS).build())
        val out = ByteArrayOutputStream()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { recorder().record(url("/forever.ts"), emptyMap(), HttpPolicy.ALLOW_HTTP, soon(), out) }
        delay(500)
        job.cancel()
        withTimeout(3_000) { job.join() }
        assertThat(job.isCancelled).isTrue()
    }
}
