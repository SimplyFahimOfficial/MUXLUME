package com.muxlume.app.ui.guide

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ProgramEntity
import com.muxlume.app.data.epg.EpgTime
import com.muxlume.app.reminders.Notifications
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.SectionLabel
import kotlinx.coroutines.launch

/** A programme's details with Watch and, for programmes that haven't started, a reminder. */
@Composable
fun ProgramSheet(item: ItemEntity, program: ProgramEntity, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val reminder by c.db.reminders().reminderFor(item.sourceId, item.stableKey, program.start).collectAsStateWithLifecycle(null)
    val canRemind = remember(program) { program.start > System.currentTimeMillis() / MILLIS }

    fun toast(id: Int, vararg args: Any) = Toast.makeText(context, res.getString(id, *args), Toast.LENGTH_SHORT).show()

    fun addReminder() = scope.launch {
        val saved = c.epg.addReminder(item.sourceId, item.stableKey, item.name, program)
        if (saved == null) {
            toast(R.string.guide_already_started)
        } else {
            c.reminders.schedule(saved)
            toast(R.string.guide_reminder_set, EpgTime.time(program.start))
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) addReminder() else toast(R.string.guide_notifications_denied)
    }

    fun onReminderClick() {
        val existing = reminder
        when {
            existing != null -> scope.launch {
                c.reminders.cancel(existing.id)
                c.epg.removeReminder(existing.id)
                toast(R.string.guide_reminder_removed)
            }
            Notifications.allowed(context) -> addReminder()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> toast(R.string.guide_notifications_off)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuxTheme.Surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Mux.ScreenPadding).padding(bottom = 28.dp),
        ) {
            Text(item.name.uppercase(), style = SectionLabel, color = MuxTheme.Accent, maxLines = 1)
            Text(program.title, style = MaterialTheme.typography.headlineSmall, color = MuxTheme.TextPrimary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(
                "${EpgTime.date(program.start)} · ${EpgTime.range(program.start, program.stop)}",
                style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary,
            )
            program.category?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MuxTheme.TextSecondary) }
            program.description?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MuxTheme.TextPrimary)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.guide_watch_channel), onWatch, icon = Icons.Rounded.PlayArrow)
                if (canRemind) {
                    SecondaryButton(
                        stringResource(if (reminder != null) R.string.guide_remove_reminder else R.string.guide_remind_me), ::onReminderClick,
                        icon = if (reminder != null) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications,
                    )
                }
            }
        }
    }
}

private const val MILLIS = 1000L
