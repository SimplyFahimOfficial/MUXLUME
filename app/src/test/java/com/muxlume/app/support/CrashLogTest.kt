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

    @Test
    fun `links are removed everywhere in the trace, including causes`() {
        val cause = IOException("unexpected end of stream on https://me:s3cret@provider.example:8443/live/me/s3cret/12.ts?token=abc123")
        log.record(Thread.currentThread(), IllegalStateException("playlist HTTP://Host.Example/list.m3u?pass=hunter2 failed", cause))
        val text = file.readText()
        assertThat(text).contains("[link removed]")
        listOf("s3cret", "abc123", "hunter2", "provider.example", "Host.Example").forEach { assertThat(text).doesNotContain(it) }
        assertThat(text).contains("IllegalStateException") // the rest of the report is kept
        assertThat(text).contains("Caused by: java.io.IOException")
        assertThat(text).contains("MUXLUME 0.1.0 (1) on Android 15, Test Phone")
    }

    @Test
    fun `web and RTSP links are redacted in any case, text around them is not`() {
        assertThat(CrashLog.redact("see https://a.example/x?y=1 and rtsp://cam.example/1, then Http://b.example")).isEqualTo("see [link removed] and [link removed] then [link removed]")
        assertThat(CrashLog.redact("no links here: host:8080/path")).isEqualTo("no links here: host:8080/path")
        assertThat(CrashLog.redact("ftp://x.example/file")).isEqualTo("ftp://x.example/file") // not a stream or playlist scheme
    }

    @Test
    fun `the file never grows past 64 KB`() {
        log.record(Thread.currentThread(), RuntimeException("x".repeat(500_000)))
        assertThat(file.length()).isAtMost(CrashLog.MAX_BYTES.toLong())
        assertThat(file.length()).isGreaterThan(60_000) // it kept as much as it may, starting from the top
        assertThat(file.readText()).startsWith("MUXLUME 0.1.0")
    }

    @Test
    fun `a second crash replaces the first, and clear removes the log`() {
        log.record(Thread.currentThread(), RuntimeException("alpha-error"))
        log.record(Thread.currentThread(), RuntimeException("beta-error"))
        assertThat(log.read()).contains("beta-error")
        assertThat(log.read()).doesNotContain("alpha-error")
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
        assertThat(log.read()).contains("boom at [link removed]")
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
