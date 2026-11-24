package com.muxlume.app.support

import java.io.File
import java.time.Instant

/**
 * The last crash, kept on the device only, for the user to share if they choose to. An uncaught exception is written as the
 * types and stack frames of it and its causes, and never its message: the text of an exception from the network stack, the system or
 * a provider can quote a stream or playlist address, a login, a host or whatever a server sent, and no pattern finds all of that.
 * What helps to fix a crash, where it happened and what kind of failure it was, is kept. The file is capped at [MAX_BYTES], then the
 * error is handed to the previous handler so Android ends the process as usual. There is no third-party crash reporting.
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
            val text = "${header()}\n${Instant.now()}\nthread: ${thread.name}\n\n${describe(error)}"
            file.writeBytes(text.encodeToByteArray().let { if (it.size > MAX_BYTES) it.copyOf(MAX_BYTES) else it })
        }
    }

    fun exists(): Boolean = file.isFile && file.length() > 0

    fun read(): String? = if (exists()) runCatching { file.readText() }.getOrNull() else null

    /** Throws if the report is still there afterwards. */
    fun clear() {
        check(file.delete() || !file.exists()) { "The crash report could not be deleted" }
    }

    companion object {
        const val MAX_BYTES = 64 * 1024

        /** The type and the stack frames of [error] and of each cause (once each), with no message. */
        fun describe(error: Throwable): String = buildString {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = error
            while (current != null && seen.add(current)) {
                append(if (seen.size == 1) "" else "Caused by: ").append(current.javaClass.name).append('\n')
                current.stackTrace.forEach { append("\tat ").append(it).append('\n') }
                current = current.cause
            }
        }
    }
}
