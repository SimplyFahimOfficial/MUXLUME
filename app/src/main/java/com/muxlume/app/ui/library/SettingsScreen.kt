package com.muxlume.app.ui.library

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.muxlume.app.container
import com.muxlume.app.data.AppSettings
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
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader("Settings", onSearch = null, onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp)) {
            LinkRow(Icons.Rounded.Palette, "Appearance", "Theme, accent colour, AMOLED, motion", nav::appearance)
            Spacer(Modifier.height(16.dp))
            SectionHeader("Playback")
            Toggle("Background audio", "Keep the sound playing after you leave the app. Off: playback pauses (picture-in-picture keeps playing).", settings.backgroundPlayback) {
                scope.launch { c.settings.setBackgroundPlayback(it) }
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader("Privacy and data")
            Text(
                "MUXLUME has no account, ads, analytics or trackers. It only connects to the playlists, provider servers and artwork links you add. " +
                    "Playlist links and provider passwords are encrypted with a key that stays in this device's secure hardware, and nothing is included in Android backups.",
                style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary, modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 8.dp),
            )
            LinkRow(Icons.Rounded.History, "Clear watch history", "Resume positions and recently watched", { confirm = "history" })
            LinkRow(Icons.Rounded.DeleteForever, "Delete all data", "Playlists, credentials, favorites, history and cached images", { confirm = "all" })
        }
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "all") "Delete all data?" else "Clear watch history?") },
            text = { Text(if (what == "all") "Everything MUXLUME stored on this device is removed. This can't be undone." else "Resume positions and the recently watched list are removed.") },
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
                        Toast.makeText(context, if (what == "all") "All data deleted" else "Watch history cleared", Toast.LENGTH_SHORT).show()
                        if (what == "all") nav.home()
                    }
                }) { Text(if (what == "all") "Delete" else "Clear", color = MuxTheme.Error) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel", color = MuxTheme.Primary) } },
            containerColor = MuxTheme.Raised,
        )
    }
}
