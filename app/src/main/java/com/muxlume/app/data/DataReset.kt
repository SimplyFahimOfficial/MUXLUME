package com.muxlume.app.data

import android.content.ContentResolver
import android.content.Intent
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.lock.ParentalLock
import com.muxlume.app.support.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** The access the app keeps to files and folders the user picked: playlist files (read) and the recordings folder (read and write). */
interface UriGrants {
    fun releaseAll()
}

class ResolverGrants(private val resolver: ContentResolver) : UriGrants {
    override fun releaseAll() {
        for (grant in resolver.persistedUriPermissions) {
            val flags = releaseFlags(grant.isReadPermission, grant.isWritePermission)
            if (flags != 0) resolver.releasePersistableUriPermission(grant.uri, flags)
        }
    }

    companion object {
        /** A grant is released only for the modes it holds: releasing read alone leaves a recordings folder writable. */
        fun releaseFlags(read: Boolean, write: Boolean) =
            (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    }
}

/** What a reset reaches besides the database and the settings file, and the two things it stops first. */
class ResetTargets(
    val crashLog: CrashLog,
    val imageCache: File,
    val grants: UriGrants,
    val stopPlayback: suspend () -> Unit,
    val stopRecordings: () -> Unit,
)

/**
 * Settings → Delete all data: everything the app stored, while it keeps running. Recording files in the folder the user chose and
 * backup files they saved are theirs and are left alone.
 *
 * It stops what writes first (playback, a running recording), then clears the database, the PIN, the crash report, the image cache
 * and the access to picked files, and the settings file last. Every step can be repeated, so a reset that was interrupted or failed
 * half way is finished by running it again; and until the settings file is gone the app still looks set up, so the user can find
 * the button again. Scheduled work needs no step of its own: every table of the database cascades from its playlist, so a worker or
 * a late history write that outlives the reset cannot store anything, and the settings that schedule background work go with the file.
 * It runs to the end even if the screen that started it is left.
 */
class DataReset(
    private val db: MuxlumeDatabase,
    private val settings: SettingsRepository,
    private val lock: ParentalLock,
    private val targets: ResetTargets,
    /** How long to wait for a running recording to close its file. */
    private val recordingWaitMs: Long = RECORDING_WAIT_MS,
) {
    suspend fun run() = withContext(NonCancellable + Dispatchers.IO) {
        targets.stopPlayback()
        if (db.recordings().anyRunning()) {
            targets.stopRecordings()
            // It finishes its row and closes (or removes, if empty) its file; the grant on the folder is released only after.
            var waited = 0L
            while (db.recordings().anyRunning() && waited < recordingWaitMs) {
                delay(POLL_MS)
                waited += POLL_MS
            }
        }
        db.clearAllTables()
        lock.clear()
        targets.crashLog.clear()
        check(!targets.imageCache.exists() || targets.imageCache.deleteRecursively()) { "The image cache could not be deleted" }
        targets.grants.releaseAll()
        settings.resetAll()
    }

    private companion object {
        const val RECORDING_WAIT_MS = 5_000L
        const val POLL_MS = 100L
    }
}
