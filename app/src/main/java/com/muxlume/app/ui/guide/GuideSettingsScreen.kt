package com.muxlume.app.ui.guide

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.AppSettings
import com.muxlume.app.data.db.ReminderEntity
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.epg.EpgTime
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.Toggle
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GuideSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val sources: StateFlow<List<SourceEntity>> = c.sources.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sources that have a guide link (their own, the playlist's, or an Xtream account). Reading it decrypts the source secret. */
    val withGuideLink: StateFlow<Set<Long>> = c.sources.sources.map { list ->
        withContext(Dispatchers.IO) { list.filter { runCatching { c.sources.guideUrl(it) }.getOrNull() != null }.map { it.id }.toSet() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val refreshing = MutableStateFlow<Set<Long>>(emptySet())

    val reminders: StateFlow<List<ReminderEntity>> = c.db.epg().upcomingReminders(System.currentTimeMillis() / MILLIS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Runs in the app scope, so leaving the screen doesn't abort a download. */
    fun setEnabled(id: Long, on: Boolean) {
        c.appScope.launch {
            c.epg.setEnabled(id, on)
            if (on) refresh(id)
        }
    }

    fun refresh(id: Long) {
        if (id in refreshing.value) return
        refreshing.update { it + id }
        c.appScope.launch {
            try {
                c.epg.refresh(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stored on the source (epgError) and shown on its card.
            } finally {
                refreshing.update { it - id }
            }
        }
    }

    fun deleteReminder(r: ReminderEntity) {
        viewModelScope.launch {
            c.reminders.cancel(r.id)
            c.epg.removeReminder(r.id)
        }
    }

    private companion object {
        const val MILLIS = 1000L
    }
}

@Composable
fun GuideSettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val c = context.container
    val vm: GuideSettingsViewModel = viewModel { GuideSettingsViewModel(c) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val withGuide by vm.withGuideLink.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.guide_settings_title), onSearch = null, onBack = nav::back)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.guide_settings_intro), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding),
            )
            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.guide_playlists))
            if (sources.isEmpty()) {
                Text(stringResource(R.string.guide_no_playlists), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary, modifier = Modifier.padding(Mux.ScreenPadding))
            }
            sources.forEach { s ->
                SourceGuideCard(
                    s, hasLink = s.id in withGuide, refreshing = s.id in refreshing,
                    onToggle = { vm.setEnabled(s.id, it) }, onRefresh = { vm.refresh(s.id) }, onMatch = { nav.guideMapping(s.id) },
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.guide_background_updates))
            Toggle(stringResource(R.string.guide_wifi_only), stringResource(R.string.guide_wifi_only_body), settings.guideWifiOnly, { scope.launch { c.settings.setGuideWifiOnly(it) } })

            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.guide_reminders))
            if (reminders.isEmpty()) {
                Text(stringResource(R.string.guide_no_reminders), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary, modifier = Modifier.padding(Mux.ScreenPadding))
            }
            reminders.forEach { r -> ReminderRow(r, onDelete = { vm.deleteReminder(r) }) }
        }
    }
}

@Composable
private fun SourceGuideCard(s: SourceEntity, hasLink: Boolean, refreshing: Boolean, onToggle: (Boolean) -> Unit, onRefresh: () -> Unit, onMatch: () -> Unit) {
    Column(Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 6.dp).fillMaxWidth().clip(Mux.CardShape).background(MuxTheme.Surface)) {
        Text(s.name, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp))
        val (status, isError) = when {
            !hasLink -> stringResource(R.string.guide_no_link) to false
            s.epgError != null && s.guideEnabled -> s.epgError to true
            !s.guideEnabled -> stringResource(R.string.guide_off) to false
            s.epgRefreshedAt == null -> stringResource(R.string.guide_not_downloaded) to false
            else -> stringResource(
                R.string.guide_status, pluralStringResource(R.plurals.guide_programmes, s.epgPrograms, s.epgPrograms),
                DateUtils.getRelativeTimeSpanString(s.epgRefreshedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            ) to false
        }
        Text(status!!, style = MaterialTheme.typography.bodySmall, color = if (isError) MuxTheme.Error else MuxTheme.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        Toggle(stringResource(R.string.guide_download), stringResource(R.string.guide_download_body), s.guideEnabled, onToggle, enabled = hasLink)
        if (s.guideEnabled) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.padding(12.dp), color = MuxTheme.Accent, strokeWidth = 2.dp)
                } else {
                    SecondaryButton(stringResource(R.string.guide_refresh_now), onRefresh)
                }
                SecondaryButton(stringResource(R.string.guide_match_channels), onMatch, enabled = s.epgPrograms > 0)
            }
        }
    }
}

@Composable
private fun ReminderRow(r: ReminderEntity, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Mux.ScreenPadding, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${r.channelName} · ${EpgTime.date(r.startAt)} ${EpgTime.time(r.startAt)}", style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onDelete) { Icon(Icons.Rounded.Close, stringResource(R.string.guide_delete_reminder, r.title), tint = MuxTheme.TextSecondary) }
    }
}
