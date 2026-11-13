package com.muxlume.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.muxlume.app.MainActivity
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ReminderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Programme reminders: an inexact alarm (`setAndAllowWhileIdle`, so no exact-alarm permission) a few
 * minutes before the programme, which posts a notification. Android may delay inexact alarms by a
 * few minutes. Alarms don't survive a reboot or an app update, so [ReminderReceiver] re-arms them
 * from the database.
 */
class ReminderScheduler(private val context: Context) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    fun schedule(reminder: ReminderEntity) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.notifyAt * MILLIS, intentFor(reminder.id))
    }

    fun cancel(id: Long) = alarms.cancel(intentFor(id))

    private fun intentFor(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_FIRE = "com.muxlume.app.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        private const val MILLIS = 1000L
    }
}

/** Not exported: it only receives its own alarm and the system's boot/update broadcasts. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val pending = goAsync()
        container.appScope.launch(Dispatchers.IO) {
            try {
                val dao = container.db.reminders()
                when (intent.action) {
                    ReminderScheduler.ACTION_FIRE -> {
                        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1)
                        container.lock.load()
                        dao.reminder(id, container.lock.unlockedNow())?.let { Notifications.show(context, it) } // none while its group is locked
                        dao.deleteReminder(id)
                    }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                        dao.remindersAfter(System.currentTimeMillis() / MILLIS).forEach { ReminderScheduler(context).schedule(it) }.also { container.recordings.rearm() }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val MILLIS = 1000L
    }
}

object Notifications {
    private const val CHANNEL = "reminders"
    const val RECORDING_CHANNEL = "recording"

    fun createChannels(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(NotificationChannel(RECORDING_CHANNEL, context.getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW))
    }

    /** True if notifications can be shown (on Android 13+ the user has granted the permission). */
    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context, reminder: ReminderEntity) {
        if (!allowed(context)) return
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochSecond(reminder.startAt).atZone(ZoneId.systemDefault()))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminders_starting_soon, reminder.title))
            .setContentText(context.getString(R.string.reminders_channel_at, reminder.channelName, time))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        // allowed() checked the permission above.
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(reminder.id.toInt(), notification)
    }
}
