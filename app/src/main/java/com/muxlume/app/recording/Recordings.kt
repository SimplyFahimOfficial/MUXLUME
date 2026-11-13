package com.muxlume.app.recording

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.muxlume.app.MainActivity
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.SettingsRepository
import com.muxlume.app.data.db.MuxlumeDatabase
import com.muxlume.app.data.db.ProgramEntity
import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.reminders.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** How a recording starts. Android lets a service start from the background after an exact alarm or a tap on a notification, not after an inexact alarm. */
object RecordingPlan {
    enum class Start { NOW, EXACT_ALARM, NOTIFICATION }

    fun start(startAt: Long, now: Long, canScheduleExact: Boolean): Start = when {
        startAt <= now -> Start.NOW
        canScheduleExact -> Start.EXACT_ALARM
        else -> Start.NOTIFICATION
    }
}

/** Records a programme: saves it, then starts it now, or arms an alarm for its start. */
class Recordings(
    private val context: Context,
    private val db: MuxlumeDatabase,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data object NoFolder : Result
        data object Ended : Result
        data object Already : Result
        data object Started : Result
        class Scheduled(val unattended: Boolean) : Result
    }

    private val scheduler get() = RecordingScheduler(context)

    suspend fun add(sourceId: Long, stableKey: String, channelName: String, program: ProgramEntity): Result {
        val now = clock() / MILLIS
        if (program.stop <= now) return Result.Ended
        if (settings.current().recordingFolder == null) return Result.NoFolder
        val draft = RecordingEntity(
            sourceId = sourceId, stableKey = stableKey, channelName = channelName.take(MAX_NAME), title = program.title.take(MAX_NAME),
            startAt = program.start, endAt = program.stop, state = RecordingState.SCHEDULED,
        )
        val id = db.recordings().insert(draft).takeIf { it > 0 } ?: return Result.Already
        return when (RecordingPlan.start(program.start, now, scheduler.canScheduleExact())) {
            RecordingPlan.Start.NOW -> {
                ContextCompat.startForegroundService(context, RecordingService.startIntent(context, id))
                Result.Started
            }
            RecordingPlan.Start.EXACT_ALARM -> Result.Scheduled(unattended = true).also { scheduler.schedule(draft.copy(id = id), exact = true) }
            RecordingPlan.Start.NOTIFICATION -> Result.Scheduled(unattended = false).also { scheduler.schedule(draft.copy(id = id), exact = false) }
        }
    }

    /** Alarms do not survive a reboot or an update: arm the future ones again, and give up on those whose time has passed. */
    suspend fun rearm() {
        val now = clock() / MILLIS
        db.recordings().markMissed(now)
        db.recordings().scheduledAfter(now).forEach { scheduler.schedule(it, exact = scheduler.canScheduleExact()) }
    }

    suspend fun cleanUpAfterCrash() {
        db.recordings().failRunning()
        db.recordings().markMissed(clock() / MILLIS)
    }

    private companion object {
        const val MILLIS = 1000L
        const val MAX_NAME = 200
    }
}

class RecordingScheduler(private val context: Context) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /**
     * [exact]: the alarm starts the service itself. Otherwise it only posts a notification whose tap starts it,
     * because an inexact alarm may not start a foreground service.
     */
    fun schedule(recording: RecordingEntity, exact: Boolean) {
        val at = recording.startAt * MILLIS
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, startIntent(recording.id))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, dueIntent(recording.id))
    }

    fun cancel(id: Long) {
        alarms.cancel(startIntent(id))
        alarms.cancel(dueIntent(id))
    }

    private fun startIntent(id: Long): PendingIntent = PendingIntent.getForegroundService(
        context, id.toInt(), RecordingService.startIntent(context, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dueIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(), Intent(context, RecordingReceiver::class.java).setAction(RecordingReceiver.ACTION_DUE).putExtra(RecordingService.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val MILLIS = 1000L
    }
}

/** The inexact fallback: a notification at the programme's start whose tap (a user action) may start the recording. Not exported. */
class RecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DUE) return
        val id = intent.getLongExtra(RecordingService.EXTRA_ID, -1)
        val container = context.container
        val pending = goAsync()
        container.appScope.launch(Dispatchers.IO) {
            try {
                container.lock.load()
                val recording = container.db.recordings().get(id)?.takeIf { it.state == RecordingState.SCHEDULED } ?: return@launch
                // A channel in a locked group does not announce itself while locked.
                if (!container.lock.unlockedNow() && container.db.items().isLockedKey(recording.sourceId, recording.stableKey)) return@launch
                if (!Notifications.allowed(context)) return@launch
                val start = PendingIntent.getForegroundService(
                    context, id.toInt(), RecordingService.startIntent(context, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = NotificationCompat.Builder(context, Notifications.RECORDING_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.recording_due_title, recording.title))
                    .setContentText(context.getString(R.string.recording_due_text, recording.channelName))
                    .setContentIntent(start)
                    .addAction(0, context.getString(R.string.recording_start), start)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + id.toInt(), notification)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DUE = "com.muxlume.app.RECORDING_DUE"
        private const val NOTIFICATION_BASE = 1_000_000
    }
}
