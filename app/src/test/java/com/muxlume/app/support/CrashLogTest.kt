package com.muxlume.app.support

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CrashLogTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var log: CrashLog
    private val saved = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        file = folder.root.resolve("crash.log")
        log = CrashLog(file) { "MUXLUME 0.1.0 (1) on Android 15, Test Phone" }
    }

    @After fun tearDown() = Thread.setDefaultUncaughtExceptionHandler(saved)

    /** Synthetic values that must never reach a shared report, written the ways a message can carry them. */
    private val markers = listOf("SYNTH-PASS-7731", "SYNTH-TOKEN-4410", "synth-host.invalid", "203.0.113.77", "SYNTH-USER")

    private fun messageWithMarkers() =
        "unexpected end of stream on https://SYNTH-USER:SYNTH-PASS-7731@synth-host.invalid:8443/live/SYNTH-USER/SYNTH-PASS-7731/12.ts?token=SYNTH-TOKEN-4410 " +
            "Failed to connect to synth-host.invalid/203.0.113.77:8443 (SYNTH-TOKEN-4410 written without a link)"

    @Test
    fun `a report holds the types and stack frames of the error and its causes, and none of their messages`() {
        val cause = IOException(messageWithMarkers())
        log.record(Thread.currentThread(), IllegalStateException(messageWithMarkers(), cause))
        val text = file.readText()
        markers.forEach { assertThat(text).doesNotContain(it) }
        assertThat(text).doesNotContain("unexpected end of stream")
        assertThat(text).contains("java.lang.IllegalStateException\n")
        assertThat(text).contains("Caused by: java.io.IOException\n")
        assertThat(text).contains("\tat com.muxlume.app.support.CrashLogTest") // where it happened
        assertThat(text).contains("MUXLUME 0.1.0 (1) on Android 15, Test Phone")
    }

    @Test
    fun `the cause chain is followed once, even when it loops`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val text = CrashLog.describe(b)
        assertThat(text.lines().count { it.startsWith("java.lang.RuntimeException") || it.startsWith("Caused by: java.lang.RuntimeException") }).isEqualTo(2)
    }

    @Test
    fun `the file never grows past 64 KB`() {
        val deep = RuntimeException("x")
        deep.stackTrace = Array(5_000) { StackTraceElement("com.muxlume.app.Synthetic", "frame$it", "Synthetic.kt", it) }
        log.record(Thread.currentThread(), deep)
        assertThat(file.length()).isAtMost(CrashLog.MAX_BYTES.toLong())
        assertThat(file.length()).isGreaterThan(60_000) // it kept as much as it may, starting from the top
        assertThat(file.readText()).startsWith("MUXLUME 0.1.0")
    }

    @Test
    fun `a second crash replaces the first, and clear removes the log`() {
        log.record(Thread.currentThread(), IllegalStateException("alpha"))
        log.record(Thread.currentThread(), UnsupportedOperationException("beta"))
        assertThat(log.read()).contains("UnsupportedOperationException")
        assertThat(log.read()).doesNotContain("IllegalStateException")
        assertThat(log.exists()).isTrue()
        log.clear()
        assertThat(log.exists()).isFalse()
        assertThat(log.read()).isNull()
    }

    @Test
    fun `the installed handler writes the log and then calls the previous handler`() {
        val seen = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        log.install()
        val boom = IllegalArgumentException("boom at https://x.example/secret")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
        assertThat(seen).containsExactly(boom)
        assertThat(log.read()).contains("java.lang.IllegalArgumentException")
        assertThat(log.read()).doesNotContain("x.example")
    }

    @Test
    fun `a log that cannot be written does not hide the crash`() {
        val broken = CrashLog(folder.newFolder("is-a-directory")) { "header" } // writing to a directory fails
        val seen = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        broken.install()
        val boom = RuntimeException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
        assertThat(seen).containsExactly(boom)
        assertThat(broken.exists()).isFalse()
    }
}
