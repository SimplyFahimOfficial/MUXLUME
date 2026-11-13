package com.muxlume.app.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.EpgChannelEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.PillRow
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.live.isLoadedEmpty
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fix the guide links by hand: pick a guide channel for channels the automatic matching missed, or undo a manual choice. */
@Composable
fun GuideMappingScreen(sourceId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val unlocked by c.lock.unlocked.collectAsStateWithLifecycle()
    val unmatchedCount by c.db.guide().unmatchedCount(sourceId, unlocked).collectAsStateWithLifecycle(0)
    val pager = remember(sourceId, tab, unlocked) {
        Pager(PagingConfig(pageSize = PAGE_SIZE)) { if (tab == 0) c.db.guide().unmatchedPage(sourceId, unlocked) else c.db.guide().manualPage(sourceId, unlocked) }.flow
    }
    val items = pager.collectAsLazyPagingItems()
    var picking by remember { mutableStateOf<ItemEntity?>(null) }

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(stringResource(R.string.guide_mapping_title), onSearch = null, onBack = onBack)
        PillRow(listOf(stringResource(R.string.guide_unmatched), stringResource(R.string.guide_matched_by_you)), tab, { tab = it })
        if (tab == 0) {
            Text(
                pluralStringResource(R.plurals.guide_unmatched_count, unmatchedCount, unmatchedCount),
                style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 8.dp),
            )
        }
        if (items.isLoadedEmpty) {
            EmptyState(
                stringResource(R.string.guide_mapping_title),
                stringResource(if (tab == 0) R.string.guide_nothing_unmatched else R.string.guide_nothing_manual),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items.itemCount, key = items.itemKey { it.id }) { i ->
                    val item = items[i] ?: return@items // placeholder while the page loads
                    MappingRow(
                        item, manual = tab == 1, onChoose = { picking = item },
                        onUnlink = { scope.launch { c.epg.removeLink(item.sourceId, item.stableKey) } },
                    )
                }
            }
        }
    }
    picking?.let { item ->
        GuideChannelPicker(
            sourceId, item.name, onDismiss = { picking = null },
            onPick = { channel ->
                scope.launch { c.epg.setManualLink(item.sourceId, item.stableKey, channel.channelId) }
                picking = null
            },
        )
    }
}

@Composable
private fun MappingRow(item: ItemEntity, manual: Boolean, onChoose: () -> Unit, onUnlink: () -> Unit) {
    val c = LocalContext.current.container
    val guideChannel by c.db.guide().manualChannel(item.sourceId, item.stableKey).collectAsStateWithLifecycle(null)
    Row(
        Modifier.fillMaxWidth().padding(start = Mux.ScreenPadding, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (manual) guideChannel else item.groupTitle.ifEmpty { null }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (manual) {
            IconButton(onUnlink) { Icon(Icons.Rounded.Close, stringResource(R.string.guide_unlink, item.name), tint = MuxTheme.TextSecondary) }
        } else {
            SecondaryButton(stringResource(R.string.guide_choose), onChoose)
        }
    }
}

/** Search the guide's channels by name and pick one. */
@Composable
private fun GuideChannelPicker(sourceId: Long, forChannel: String, onDismiss: () -> Unit, onPick: (EpgChannelEntity) -> Unit) {
    val epg = LocalContext.current.container.epg
    var query by remember { mutableStateOf(forChannel) }
    val results by produceState<List<EpgChannelEntity>>(emptyList(), query) {
        delay(SEARCH_DELAY_MS) // wait for a pause in typing
        value = epg.findGuideChannels(sourceId, query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MuxTheme.Raised,
        title = { Text(stringResource(R.string.guide_choose_for, forChannel)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it.take(MAX_QUERY) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.guide_search_channels)) })
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (results.isEmpty()) Text(stringResource(R.string.guide_no_results), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
                    results.forEach { ch ->
                        Column(Modifier.fillMaxWidth().pressable({ onPick(ch) }, label = ch.name).padding(vertical = 8.dp)) {
                            Text(ch.name, style = MaterialTheme.typography.titleSmall, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ch.channelId, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_cancel), color = MuxTheme.Primary) } },
    )
}

private const val PAGE_SIZE = 40
private const val SEARCH_DELAY_MS = 200L
private const val MAX_QUERY = 100
