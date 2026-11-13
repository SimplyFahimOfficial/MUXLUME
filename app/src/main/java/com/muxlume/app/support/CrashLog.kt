package com.muxlume.app.support

import java.io.File
import java.time.Instant

/**
 * The last crash, kept on the device only, for the user to share if they choose to. An uncaught exception is
 * written as text with every link removed (exception messages from the network stack often quote the stream or
 * playlist URL, which can hold a login), capped at [MAX_BYTES], then handed to the previous handler so Android
 * ends the process as usual. There is no third-party crash reporting.
 *
 * shortcut: only web and RTSP links are removed; a login or token written some other way in a message is not.
 * Extend [LINK] if a new case turns up.
 */
class CrashLog(private val file: File, private val header: () -> String) {
    /** Takes over as the default handler, calling the one that was there. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Never throws: the process is already dying, and a failure here must not hide the crash. */
    fun record(thread: Thread, error: Throwable) {
        runCatching {
            val text = redact("${header()}\n${Instant.now()}\nthread: ${thread.name}\n\n${error.stackTraceToString()}")
            file.writeBytes(text.encodeToByteArray().let { if (it.size > MAX_BYTES) it.copyOf(MAX_BYTES) else it })
        }
    }

    fun exists(): Boolean = file.isFile && file.length() > 0

    fun read(): String? = if (exists()) runCatching { file.readText() }.getOrNull() else null

    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        private val LINK = Regex("(?:https?|rtsp)://\\S*", RegexOption.IGNORE_CASE)

        /** Every web or RTSP link, up to the next space, becomes a marker. */
        fun redact(text: String): String = LINK.replace(text, "[link removed]")
    }
}
