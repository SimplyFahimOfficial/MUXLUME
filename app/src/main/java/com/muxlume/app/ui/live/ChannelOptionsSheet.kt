package com.muxlume.app.ui.live

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ChannelPrefEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.launch

/** What a long press on a channel offers: pin to the top of the lists, rename, hide. */
@Composable
fun ChannelOptionsSheet(item: ItemEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val prefs = context.container.db.channelPrefs()
    val scope = rememberCoroutineScope()
    val pref by prefs.observe(item.sourceId, item.stableKey).collectAsStateWithLifecycle(null)
    var renaming by remember { mutableStateOf(false) }

    fun edit(change: (ChannelPrefEntity) -> ChannelPrefEntity) = scope.launch {
        prefs.edit(item.sourceId, item.stableKey, change)
        onDismiss()
    }

    if (renaming) {
        // Saving the playlist's own name again is not a rename.
        RenameDialog(item.name, onSave = { name -> edit { it.copy(customName = name.takeUnless { n -> n == item.name && it.customName == null }) } }, onCancel = onDismiss)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuxTheme.Surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                item.name, style = MaterialTheme.typography.titleLarge, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 8.dp).semantics { heading() },
            )
            val pinned = pref?.pinned == true
            OptionRow(Icons.Rounded.PushPin, stringResource(if (pinned) R.string.channel_unpin else R.string.channel_pin)) { edit { it.copy(pinned = !pinned) } }
            OptionRow(Icons.Rounded.Edit, stringResource(R.string.channel_rename)) { renaming = true }
            OptionRow(Icons.Rounded.VisibilityOff, stringResource(R.string.channel_hide)) {
                Toast.makeText(context, res.getString(R.string.channel_hidden, item.name), Toast.LENGTH_LONG).show()
                edit { it.copy(hidden = true) }
            }
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = Mux.ScreenPadding, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MuxTheme.Primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary)
    }
}

/** [onSave] gets the trimmed name, or null to go back to the playlist's own name. */
@Composable
private fun RenameDialog(current: String, onSave: (String?) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.channel_rename_title)) },
        text = {
            Column {
                TextField(
                    text, { text = it.take(ChannelPrefEntity.MAX_NAME) },
                    label = { Text(stringResource(R.string.channel_rename_label)) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MuxTheme.Surface, unfocusedContainerColor = MuxTheme.Surface, cursorColor = MuxTheme.Accent,
                        focusedIndicatorColor = MuxTheme.Accent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Text(stringResource(R.string.channel_rename_hint), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton({ onSave(text.trim().ifEmpty { null }) }) { Text(stringResource(R.string.channel_save), color = MuxTheme.Primary) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.channel_cancel), color = MuxTheme.Primary) } },
        containerColor = MuxTheme.Raised,
    )
}
