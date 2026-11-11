package com.muxlume.app.ui.library

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.muxlume.app.container
import com.muxlume.app.data.AppSettings
import com.muxlume.app.data.BufferMode
import com.muxlume.app.ui.components.PillRow
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.Toggle
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val res = LocalResources.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.settings_settings), onSearch = null, onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp)) {
            LinkRow(Icons.Rounded.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_theme_accent_colour_amoled_motion), nav::appearance)
            LinkRow(Icons.Rounded.CalendarMonth, stringResource(R.string.guide_settings_title), stringResource(R.string.guide_settings_summary), nav::guideSettings)
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.settings_playback))
            Toggle(stringResource(R.string.settings_background_audio), stringResource(R.string.settings_keep_the_sound_playing_after), settings.backgroundPlayback) {
                scope.launch { c.settings.setBackgroundPlayback(it) }
            }
            Text(
                stringResource(R.string.settings_buffering), style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding).padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.settings_buffering_body), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 4.dp),
            )
            PillRow(
                listOf(R.string.settings_buffer_normal, R.string.settings_buffer_large, R.string.settings_buffer_small).map { stringResource(it) },
                BufferMode.entries.indexOf(settings.buffer),
                { scope.launch { c.settings.setBuffer(BufferMode.entries[it]) } },
            )
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.settings_privacy_and_data))
            Text(
                stringResource(R.string.settings_muxlume_has_no_account_ads) +
                    stringResource(R.string.settings_playlist_links_and_provider_passwords),
                style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary, modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 8.dp),
            )
            LinkRow(Icons.Rounded.History, stringResource(R.string.settings_clear_watch_history), stringResource(R.string.settings_resume_positions_and_recently_watched), { confirm = "history" })
            LinkRow(Icons.Rounded.DeleteForever, stringResource(R.string.settings_delete_all_data), stringResource(R.string.settings_playlists_credentials_favorites_history_and), { confirm = "all" })
        }
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (what == "all") R.string.settings_delete_all_data_2 else R.string.settings_clear_watch_history_2)) },
            text = { Text(stringResource(if (what == "all") R.string.settings_everything_muxlume_stored_on_this else R.string.settings_resume_positions_and_the_recently)) },
            confirmButton = {
                TextButton({
                    confirm = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (what == "all") {
                                c.db.clearAllTables()
                                context.cacheDir.resolve("images").deleteRecursively()
                                context.contentResolver.persistedUriPermissions.forEach {
                                    context.contentResolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            } else {
                                c.db.history().clear()
                            }
                        }
                        Toast.makeText(context, res.getString(if (what == "all") R.string.settings_all_data_deleted else R.string.settings_watch_history_cleared), Toast.LENGTH_SHORT).show()
                        if (what == "all") nav.home()
                    }
                }) { Text(stringResource(if (what == "all") R.string.settings_delete else R.string.settings_clear), color = MuxTheme.Error) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.settings_cancel), color = MuxTheme.Primary) } },
            containerColor = MuxTheme.Raised,
        )
    }
}
