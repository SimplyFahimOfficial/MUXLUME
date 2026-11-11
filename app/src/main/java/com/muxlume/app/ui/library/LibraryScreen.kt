package com.muxlume.app.ui.library

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.PosterCard
import com.muxlume.app.ui.components.HighlightRow
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.main.BottomBarSpace
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val sources = c.sources.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val refreshing = MutableStateFlow<Set<Long>>(emptySet())
    val watchlist = combine(c.db.items().favorites(ItemKind.MOVIE, null), c.db.items().favorites(ItemKind.SERIES, null)) { m, s -> m + s }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Refreshes in the app scope, so leaving the screen doesn't abort a long import. */
    fun refresh(id: Long) {
        if (id in refreshing.value) return
        refreshing.update { it + id }
        c.appScope.launch {
            runCatching { c.sources.refresh(id) } // failures are stored on the source and shown on its card
            refreshing.update { it - id }
        }
    }

    fun delete(id: Long) = viewModelScope.launch { c.sources.delete(id) }
}

@Composable
fun LibraryScreen(nav: Nav) {
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel { LibraryViewModel(context.container) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val watchlist by vm.watchlist.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<SourceEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomBarSpace + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TopHeader(stringResource(R.string.library_library), onSearch = nav::search) }
        item { SectionHeader(stringResource(R.string.library_your_playlists)) }
        items(sources, key = { it.id }) { s ->
            SourceCard(s, s.id in refreshing, onRefresh = { vm.refresh(s.id) }, onEdit = { nav.addSource(s.id) }, onDelete = { confirmDelete = s })
        }
        item {
            PrimaryButton(stringResource(R.string.library_add_a_playlist), { nav.addSource() }, Modifier.padding(horizontal = Mux.ScreenPadding).fillMaxWidth(), icon = Icons.Rounded.Add)
        }
        if (watchlist.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader(stringResource(R.string.library_watchlist))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = Mux.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(watchlist, key = { it.id }) { m -> PosterCard(m.name, m.logo, { nav.detail(m.sourceId, m.stableKey) }, width = 112.dp) }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)); SectionHeader(stringResource(R.string.library_more)) }
        item { LinkRow(Icons.Rounded.Settings, stringResource(R.string.library_settings), stringResource(R.string.library_appearance_playback_privacy_and_data), nav::settings) }
        item { HighlightRow(Icons.Rounded.Favorite, stringResource(R.string.library_support_my_work), stringResource(R.string.library_telegram_project_website_payment_options), nav::support) }
        item { LinkRow(Icons.AutoMirrored.Rounded.Help, stringResource(R.string.library_help_about), stringResource(R.string.library_troubleshooting_licenses_version), nav::about) }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.library_remove, s.name)) },
            text = { Text(stringResource(R.string.library_its_channels_favorites_and_watch)) },
            confirmButton = { TextButton({ vm.delete(s.id); confirmDelete = null }) { Text(stringResource(R.string.library_remove_2), color = MuxTheme.Error) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(R.string.library_cancel), color = MuxTheme.Primary) } },
            containerColor = MuxTheme.Raised,
        )
    }
}

@Composable
private fun SourceCard(s: SourceEntity, refreshing: Boolean, onRefresh: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.padding(horizontal = Mux.ScreenPadding).fillMaxWidth().clip(Mux.CardShape).background(MuxTheme.Surface).padding(start = 18.dp, top = 16.dp, bottom = 8.dp, end = 8.dp)) {
        Text(s.name, style = MaterialTheme.typography.titleLarge, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                stringResource(
                    when (s.type) {
                        SourceType.M3U_URL -> R.string.library_m3u_link
                        SourceType.M3U_FILE -> R.string.library_m3u_file
                        SourceType.XTREAM -> R.string.library_xtream_codes
                    },
                ),
                s.label.takeIf { it.isNotEmpty() },
                stringResource(R.string.library_http).takeIf { s.allowHttp },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                s.liveCount.takeIf { it > 0 }?.let { stringResource(R.string.library_channels, it) },
                s.movieCount.takeIf { it > 0 }?.let { stringResource(R.string.library_movies, it) },
                s.seriesCount.takeIf { it > 0 }?.let { stringResource(R.string.library_series, it) },
            ).joinToString(" · ").ifEmpty { stringResource(R.string.library_no_items) },
            style = MaterialTheme.typography.bodyMedium, color = MuxTheme.Primary,
        )
        val updated = s.refreshedAt?.let { stringResource(R.string.library_updated) + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) }
        val expires = s.expiresAt?.let { stringResource(R.string.library_account_expires) + DateUtils.formatDateTime(null, it * 1000, DateUtils.FORMAT_SHOW_DATE) }
        Text(
            listOfNotNull(updated, expires).joinToString(" · ").ifEmpty { stringResource(R.string.library_never_imported) },
            style = MaterialTheme.typography.bodySmall,
            color = MuxTheme.TextSecondary,
        )
        s.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MuxTheme.Error, modifier = Modifier.padding(top = 4.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (refreshing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), color = MuxTheme.Accent, strokeWidth = 2.dp)
            } else {
                IconButton(onRefresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.library_refresh, s.name), tint = MuxTheme.TextPrimary) }
            }
            IconButton(onEdit, enabled = !refreshing) { Icon(Icons.Rounded.Edit, stringResource(R.string.library_edit, s.name), tint = MuxTheme.TextPrimary) }
            IconButton(onDelete, enabled = !refreshing) { Icon(Icons.Rounded.Delete, stringResource(R.string.library_remove_3, s.name), tint = MuxTheme.TextPrimary) }
        }
    }
}

@Composable
fun LinkRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick, label = title).padding(horizontal = Mux.ScreenPadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MuxTheme.Primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MuxTheme.TextSecondary)
    }
}
