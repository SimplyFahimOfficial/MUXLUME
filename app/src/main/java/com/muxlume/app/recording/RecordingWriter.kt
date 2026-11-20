package com.muxlume.app.recording

import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.recording.RecordingException.Reason
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.OutputStream

/** How a recording ended, as stored on its row. [stopped]: the job was cancelled (a Stop or the service time limit). */
internal class RecordingOutcome(val state: RecordingState, val error: String?, val stopped: Boolean)

/** Why a write, open or close failed: a full storage, or anything else about the file (which includes access that was revoked). */
internal fun writeFailure(e: Throwable): Reason {
    val full = generateSequence(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space", ignoreCase = true) == true }
    return if (full) Reason.STORAGE_FULL else Reason.WRITE_FAILED
}

/**
 * Opens the output, copies into it with [record] and closes it, and says how that went; it never throws for those failures.
 * DONE means every step worked, closing included: a failure at any step is FAILED with its reason, and the caller keeps the bytes
 * written so far. A cancellation is a deliberate end: DONE, or TIME_LIMIT when [timedOut] says Android ended the service.
 */
@Suppress("SwallowedException") // failures are stored as a reason name; their messages may name a stream URL
internal suspend fun writeRecording(open: () -> OutputStream?, timedOut: () -> Boolean, record: suspend (OutputStream) -> Unit): RecordingOutcome {
    var reason: Reason? = null
    var stopped = false
    var out: OutputStream? = null
    try {
        out = open() ?: refuse(Reason.WRITE_FAILED)
        record(out)
    } catch (e: CancellationException) {
        stopped = true
        if (timedOut()) reason = Reason.TIME_LIMIT
    } catch (e: RecordingException) {
        reason = e.reason
    } catch (e: IOException) {
        reason = writeFailure(e)
    } catch (e: SecurityException) { // access to the folder was revoked
        reason = Reason.WRITE_FAILED
    } finally {
        // Also for an unexpected exception, which then goes on: the stream must not stay open while the caller finalizes.
        try {
            out?.close()
        } catch (e: IOException) {
            reason = reason ?: writeFailure(e) // the last buffered bytes may be lost
        } catch (e: SecurityException) {
            reason = reason ?: Reason.WRITE_FAILED
        }
    }
    return RecordingOutcome(if (reason == null) RecordingState.DONE else RecordingState.FAILED, reason?.name, stopped)
}
