package com.muxlume.app.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.lock.ParentalLock
import com.muxlume.app.support.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The access the app keeps to files and folders the user picked: playlist files (read) and the recordings folder (read and write). */
interface UriGrants {
    fun releaseAll()
}

@Suppress("SwallowedException") // see the comment where it is caught
class ResolverGrants(private val held: () -> List<Held>, private val release: (Uri, Int) -> Unit) : UriGrants {
    constructor(resolver: ContentResolver) : this(
        { resolver.persistedUriPermissions.map { Held(it.uri, it.isReadPermission, it.isWritePermission) } },
        resolver::releasePersistableUriPermission,
    )

    class Held(val uri: Uri, val read: Boolean, val write: Boolean)

    override fun releaseAll() {
        for (grant in held()) {
            val flags = releaseFlags(grant.read, grant.write)
            if (flags == 0) continue
            try {
                release(grant.uri, flags)
            } catch (e: SecurityException) {
                // Already released (a playlist deleted, a folder changed meanwhile): what is wanted. It must not stop a reset for good.
            }
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
    /** Empties the artwork cache, through the image loader that has it open. */
    val clearImages: () -> Unit,
    val grants: UriGrants,
    val stopPlayback: suspend () -> Unit,
    val stopRecordings: () -> Unit,
    /** Whether a recording is being prepared, written or closed (the database only knows the middle). */
    val recordingBusy: () -> Boolean,
)

/**
 * Settings → Delete all data: everything the app stored, while it keeps running. Recording files in the folder the user chose and
 * backup files they saved are theirs and are left alone.
 *
 * It stops what writes first (a running recording, awaited; then playback), then clears the database, the crash report, the image cache
 * and the access to picked files, and the settings file (which holds the PIN) last. Every step can be repeated, so a reset that was interrupted or failed
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
    /** True while a reset runs: the button waits, and a second tap starts nothing. */
    val running = MutableStateFlow(false)
    private val mutex = Mutex()

    /** Throws if something could not be done; running it again then picks up where it stopped. */
    suspend fun run() = mutex.withLock {
        running.value = true
        try {
            withContext(NonCancellable + Dispatchers.IO) { reset() }
        } finally {
            running.value = false
        }
    }

    private suspend fun reset() {
        if (targets.recordingBusy() || db.recordings().anyRunning()) {
            targets.stopRecordings()
            // It finishes its row and closes (or removes, if empty) its file; the grant on the folder is released only after.
            var waited = 0L
            while (targets.recordingBusy() && waited < recordingWaitMs) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            if (targets.recordingBusy()) throw UserFacingException("A recording did not stop in time")
        }
        targets.stopPlayback()
        db.clearAllTables()
        targets.crashLog.clear()
        targets.clearImages()
        targets.grants.releaseAll()
        settings.resetAll()
        lock.clear() // after the settings file, which holds the PIN: a reset that failed earlier must not have taken it
    }

    private companion object {
        const val RECORDING_WAIT_MS = 5_000L
        const val POLL_MS = 100L
    }
}
