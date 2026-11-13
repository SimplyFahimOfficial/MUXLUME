package com.muxlume.app.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.muxlume.app.MainActivity
import com.muxlume.app.MuxlumeApp
import com.muxlume.app.R
import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.playback.ResolvedStream
import com.muxlume.app.reminders.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs recordings in the foreground (type dataSync, with an ongoing notification and a Stop action). Android 15
 * allows dataSync services 6 hours in 24 while the app is in the background; [onTimeout] then closes the files
 * cleanly, keeps what was recorded and says so. Stream URLs are resolved here and never leave the service.
 */
class RecordingService : Service() {
    private val container get() = (application as MuxlumeApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    @Volatile private var timedOut = false
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Always promote first: a service started with startForegroundService must call this within seconds.
        promote()
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_START -> if (id > 0 && jobs[id] == null) jobs[id] = scope.launch { runRecording(id) }
            ACTION_STOP -> (if (id > 0) listOfNotNull(jobs[id]) else jobs.values.toList()).forEach { it.cancel() }
        }
        if (jobs.isEmpty()) stopSelf(startId)
        return START_NOT_STICKY
    }

    /** Android 15: the 6 hour limit of dataSync services is up. Finish within seconds. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        scope.launch {
            jobs.values.toList().forEach { it.cancelAndJoin() }
            stopSelf(lastStartId)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promote() {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0, stopIntent(this, -1), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.recording_notification_text, maxOf(jobs.size, 1), maxOf(jobs.size, 1)))
            .setContentIntent(open)
            .addAction(0, getString(R.string.recording_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private suspend fun runRecording(id: Long) {
        refreshNotification()
        try {
            record(id)
        } finally {
            jobs.remove(id)
            if (jobs.isEmpty()) stopSelf(lastStartId) else refreshNotification()
        }
    }

    /** Everything a recording needs before it starts; [prepare] throws [RecordingException] when something is missing. */
    private class Plan(val stream: ResolvedStream, val file: Uri, val endMs: Long)

    private suspend fun prepare(recording: RecordingEntity): Plan {
        val folder = container.settings.current().recordingFolder?.toUri() ?: refuse(RecordingException.Reason.NO_FOLDER)
        val endMs = recording.endAt * MILLIS
        if (System.currentTimeMillis() >= endMs) refuse(RecordingException.Reason.MISSED)
        container.lock.load()
        val unlocked = container.lock.unlockedNow()
        val stream = container.streams.resolve(recording.sourceId, recording.stableKey, null, unlocked)
            ?: refuse(if (!unlocked && container.db.items().isLockedKey(recording.sourceId, recording.stableKey)) RecordingException.Reason.LOCKED else RecordingException.Reason.GONE)
        val file = RecordingFiles.create(contentResolver, folder, RecordingFiles.fileName(recording.channelName, recording.title, recording.startAt))
            ?: refuse(RecordingException.Reason.WRITE_FAILED)
        return Plan(stream, file, endMs)
    }

    private suspend fun record(id: Long) {
        val dao = container.db.recordings()
        val recording = dao.get(id)?.takeIf { it.state == RecordingState.SCHEDULED } ?: return
        val plan = try {
            prepare(recording)
        } catch (e: RecordingException) {
            dao.finish(id, RecordingState.FAILED, 0, e.reason.name)
            return
        }
        dao.start(id, plan.file.toString())
        val recorder = Recorder(container.http)
        val progress = scope.launch {
            while (true) {
                delay(PROGRESS_MS)
                dao.setBytes(id, recorder.bytes)
            }
        }
        var state = RecordingState.DONE
        var error: String? = null
        try {
            val out = contentResolver.openOutputStream(plan.file, "w") ?: refuse(RecordingException.Reason.WRITE_FAILED)
            out.use { recorder.record(plan.stream.url, plan.stream.headers, plan.stream.policy, plan.endMs, it) }
        } catch (e: RecordingException) {
            state = RecordingState.FAILED
            error = e.reason.name
        } catch (e: CancellationException) {
            if (timedOut) {
                state = RecordingState.FAILED
                error = RecordingException.Reason.TIME_LIMIT.name
            }
            throw e // a Stop the user asked for ends as DONE
        } finally {
            progress.cancel()
            withContext(NonCancellable) {
                dao.finish(id, state, recorder.bytes, error)
                if (recorder.bytes == 0L) RecordingFiles.delete(contentResolver, plan.file) // nothing worth keeping
            }
        }
    }

    companion object {
        const val ACTION_START = "com.muxlume.app.RECORDING_START"
        const val ACTION_STOP = "com.muxlume.app.RECORDING_STOP"
        const val EXTRA_ID = "recording_id"
        private const val NOTIFICATION_ID = 7001
        private const val PROGRESS_MS = 10_000L
        private const val MILLIS = 1000L

        fun startIntent(context: Context, id: Long): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id)

        /** [id] below 1 stops every recording. */
        fun stopIntent(context: Context, id: Long): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id)
    }
}
