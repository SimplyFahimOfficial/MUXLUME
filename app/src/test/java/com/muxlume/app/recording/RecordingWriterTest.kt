package com.muxlume.app.recording

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.core.net.HttpPolicy
import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.recording.RecordingException.Reason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** How a recording is finalized: DONE only when opening, writing and closing all worked. */
class RecordingWriterTest {
    private val server = MockWebServer()
    private val body = ByteArray(100_000) { it.toByte() }

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    /** An output that accepts [allow] bytes and then fails writes with [error]; [closeError] fails close. */
    private class FlakyOut(private val allow: Int = Int.MAX_VALUE, private val error: IOException? = null, private val closeError: IOException? = null) : OutputStream() {
        val written = ByteArrayOutputStream()
        var closed = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (error != null && written.size() + len > allow) throw error
            written.write(b, off, len)
        }
        override fun close() { closed = true; closeError?.let { throw it } }
    }

    private suspend fun recordStream(out: OutputStream?, openError: Exception? = null, timedOut: Boolean = false): Pair<RecordingOutcome, Recorder> {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "video/mp2t").body(Buffer().write(body)).build())
        val recorder = Recorder(OkHttpClient(), minPollMs = 10, giveUpAfterMs = 400, backoffMs = listOf(20L))
        val outcome = writeRecording({ openError?.let { throw it }; out }, { timedOut }) {
            recorder.record(server.url("/live.ts").toString(), emptyMap(), HttpPolicy.ALLOW_HTTP, System.currentTimeMillis() + 20_000, it)
        }
        return outcome to recorder
    }

    private fun assertFailed(o: RecordingOutcome, reason: Reason) {
        assertThat(o.state).isEqualTo(RecordingState.FAILED)
        assertThat(o.error).isEqualTo(reason.name)
    }

    @Test
    fun `a recording that opened, wrote and closed is done`(): Unit = runBlocking {
        val out = FlakyOut()
        val (outcome, recorder) = recordStream(out)
        assertThat(outcome.state).isEqualTo(RecordingState.DONE)
        assertThat(outcome.error).isNull()
        assertThat(out.closed).isTrue()
        assertThat(recorder.bytes).isEqualTo(body.size.toLong())
    }

    @Test
    fun `an output that cannot be opened fails the recording`(): Unit = runBlocking {
        assertFailed(recordStream(null).first, Reason.WRITE_FAILED) // the provider returned no stream
        assertFailed(recordStream(null, openError = FileNotFoundException("gone")).first, Reason.WRITE_FAILED)
        assertFailed(recordStream(null, openError = SecurityException("permission revoked")).first, Reason.WRITE_FAILED)
        assertThat(server.requestCount).isEqualTo(0) // nothing was downloaded for it
    }

    @Test
    fun `a write failure fails the recording and keeps what was written`(): Unit = runBlocking {
        val out = FlakyOut(allow = 40_000, error = IOException("disk error"))
        val (outcome, recorder) = recordStream(out)
        assertFailed(outcome, Reason.WRITE_FAILED)
        assertThat(recorder.bytes).isGreaterThan(0L)
        assertThat(out.closed).isTrue()

        val (full, _) = recordStream(FlakyOut(allow = 10_000, error = IOException("write failed: ENOSPC (No space left on device)")))
        assertFailed(full, Reason.STORAGE_FULL)
    }

    @Test
    fun `a close failure after bytes were written is a failure, not done`(): Unit = runBlocking {
        val out = FlakyOut(closeError = IOException("flush failed"))
        val (outcome, recorder) = recordStream(out)
        assertFailed(outcome, Reason.WRITE_FAILED)
        assertThat(recorder.bytes).isEqualTo(body.size.toLong()) // the partial file is kept by the caller
    }

    @Test
    fun `a failure is not replaced by a later close failure`(): Unit = runBlocking {
        val outcome = writeRecording({ FlakyOut(closeError = IOException("close")) }, { false }) { throw RecordingException(Reason.NETWORK) }
        assertFailed(outcome, Reason.NETWORK)
    }

    @Test
    fun `a deliberate stop is done, and the service time limit is not`(): Unit = runBlocking {
        for (timedOut in listOf(false, true)) {
            val running = CompletableDeferred<Unit>()
            val out = FlakyOut()
            lateinit var outcome: RecordingOutcome
            val job = launch { outcome = writeRecording({ out }, { timedOut }) { it.write(1); running.complete(Unit); awaitCancellation() } }
            running.await()
            job.cancelAndJoin()
            assertThat(outcome.stopped).isTrue()
            assertThat(out.closed).isTrue()
            if (timedOut) assertFailed(outcome, Reason.TIME_LIMIT) else {
                assertThat(outcome.state).isEqualTo(RecordingState.DONE)
                assertThat(outcome.error).isNull()
            }
        }
    }
}
