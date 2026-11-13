package com.muxlume.app.ui.library

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState
import com.muxlume.app.data.epg.EpgTime
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.recording.RecordingFiles
import com.muxlume.app.recording.RecordingScheduler
import com.muxlume.app.recording.RecordingService
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.play
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModel(private val c: AppContainer, private val app: android.content.Context) : ViewModel() {
    val list = c.lock.unlocked.flatMapLatest { c.db.recordings().all(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totalBytes = c.db.recordings().totalBytes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val folder = c.settings.settings.map { it.recordingFolder }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(recording: RecordingEntity) = viewModelScope.launch {
        RecordingScheduler(app).cancel(recording.id)
        recording.fileUri?.let { RecordingFiles.delete(app.contentResolver, Uri.parse(it)) }
        c.db.recordings().delete(recording.id)
    }
}

/** Where recordings go, what is scheduled or saved, and play, stop and delete. */
@Composable
fun RecordingsScreen(nav: Nav) {
    val context = LocalContext.current
    val c = context.container
    val vm: RecordingsViewModel = viewModel { RecordingsViewModel(c, context.applicationContext) }
    val list by vm.list.collectAsStateWithLifecycle()
    val total by vm.totalBytes.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<RecordingEntity?>(null) }
    var canExact by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canExact = RecordingScheduler(context).canScheduleExact() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
        // The previous folder's permission is no longer needed; keep it only if it is the same folder.
        folder?.let { old -> if (old != tree.toString()) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) } }
        scope.launch { c.settings.setRecordingFolder(tree.toString()) }
    }

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.recording_title), onSearch = null, onBack = { nav.back() })
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(Mux.ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.recording_folder), style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary)
                    Text(
                        folder?.let { Uri.decode(Uri.parse(it).lastPathSegment.orEmpty()) } ?: stringResource(R.string.recording_folder_none),
                        style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary,
                    )
                    SecondaryButton(stringResource(R.string.recording_choose_folder), { picker.launch(null) })
                    if (!canExact) {
                        Text(stringResource(R.string.recording_exact_note), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary)
                        SecondaryButton(
                            stringResource(R.string.recording_allow_alarms),
                            { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) },
                        )
                    }
                    if (total > 0) Text(stringResource(R.string.recording_used, Formatter.formatFileSize(context, total)), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary)
                }
            }
            if (list.isEmpty()) {
                item { EmptyState(stringResource(R.string.recording_empty_title), stringResource(R.string.recording_empty_body)) }
            }
            items(list, key = { it.id }) { r ->
                RecordingRow(
                    r, context,
                    onPlay = { scope.launch { if (context.play(listOf(MediaIds.request(MediaIds.ofRecording(r.id), r.title, r.channelName, null, ItemKind.MOVIE)))) nav.player() } },
                    onStop = { context.startService(RecordingService.stopIntent(context, r.id)) },
                    onDelete = { deleting = r },
                )
            }
        }
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.recording_delete_title)) },
            text = { Text(stringResource(R.string.recording_delete_body)) },
            confirmButton = { TextButton({ vm.delete(r); deleting = null }) { Text(stringResource(R.string.recording_delete_confirm), color = MuxTheme.Error) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.channel_cancel), color = MuxTheme.Primary) } },
            containerColor = MuxTheme.Raised,
        )
    }
}

@Composable
private fun RecordingRow(r: RecordingEntity, context: android.content.Context, onPlay: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
    val state = when (r.state) {
        RecordingState.SCHEDULED -> stringResource(R.string.recording_state_scheduled)
        RecordingState.RECORDING -> stringResource(R.string.recording_state_recording)
        RecordingState.DONE -> stringResource(R.string.recording_state_done)
        RecordingState.FAILED -> stringResource(R.string.recording_state_failed, stringResource(errorText(r.error)))
    }
    val size = if (r.bytes > 0) " · " + Formatter.formatFileSize(context, r.bytes) else ""
    Row(Modifier.fillMaxWidth().padding(horizontal = Mux.ScreenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${r.channelName} · ${EpgTime.date(r.startAt)} ${EpgTime.range(r.startAt, r.endAt)}",
                style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(state + size, style = MaterialTheme.typography.bodySmall, color = if (r.state == RecordingState.FAILED) MuxTheme.Error else MuxTheme.TextSecondary)
        }
        if (r.fileUri != null && r.bytes > 0 && r.state != RecordingState.RECORDING) {
            IconButton(onPlay) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.recording_play, r.title), tint = MuxTheme.Primary) }
        }
        if (r.state == RecordingState.RECORDING) {
            IconButton(onStop) { Icon(Icons.Rounded.Stop, stringResource(R.string.recording_stop_named, r.title), tint = MuxTheme.Error) }
        } else {
            IconButton(onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.recording_delete, r.title), tint = MuxTheme.TextSecondary) }
        }
    }
}

private fun errorText(error: String?) = when (error) {
    "DASH" -> R.string.recording_error_dash
    "RTSP" -> R.string.recording_error_rtsp
    "DRM" -> R.string.recording_error_drm
    "ENCRYPTED" -> R.string.recording_error_encrypted
    "FMP4" -> R.string.recording_error_fmp4
    "NETWORK" -> R.string.recording_error_network
    "STORAGE_FULL" -> R.string.recording_error_storage_full
    "WRITE_FAILED" -> R.string.recording_error_write_failed
    "LOCKED" -> R.string.recording_error_locked
    "GONE" -> R.string.recording_error_gone
    "NO_FOLDER" -> R.string.recording_error_no_folder
    "TIME_LIMIT" -> R.string.recording_error_time_limit
    "MISSED" -> R.string.recording_error_missed
    "INTERRUPTED" -> R.string.recording_error_interrupted
    else -> R.string.recording_error_other
}
