package com.muxlume.app.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.EpgDao
import com.muxlume.app.data.db.ItemDao
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.playback.pagingSource
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.guide.SearchField
import com.muxlume.app.ui.live.isLoadedEmpty
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.flow.flowOf

private const val PAGE_SIZE = 40
private const val MAX_HITS = 100
private const val MIN_QUERY = 2
private const val SHEET_HEIGHT = 0.9f

/**
 * The channels of [list] over the video, so another one can be picked without leaving the player; the video keeps playing
 * until a row is chosen. Typing searches the live channels of the same playlist.
 */
@Composable
fun ChannelPickerSheet(primaryId: String, list: ChannelList, onPick: (ItemEntity) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val sourceId = MediaIds.parse(primaryId)?.first ?: return
    val unlocked by c.lock.unlocked.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val paged = remember(list, unlocked) { Pager(PagingConfig(pageSize = PAGE_SIZE)) { list.pagingSource(c.db, sourceId, unlocked) }.flow }.collectAsLazyPagingItems()
    val hits by remember(query, unlocked) {
        val match = ItemDao.ftsQuery(query).takeIf { query.trim().length >= MIN_QUERY }
        if (match == null) flowOf(null) else c.db.channelPrefs().searchLive(match, "%${EpgDao.likeEscape(query.trim())}%", sourceId, MAX_HITS, unlocked)
    }.collectAsStateWithLifecycle(null)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuxTheme.Surface) {
        Column(Modifier.fillMaxHeight(SHEET_HEIGHT)) {
            SearchField(query, hint = R.string.player_channels_search, autoFocus = false) { query = it }
            val found = hits
            when {
                found == null -> Paged(paged, primaryId, onPick)
                found.isEmpty() -> EmptyState(stringResource(R.string.player_channels_none), stringResource(R.string.player_channels_none_body, query))
                else -> LazyColumn { items(found, key = { it.id }) { PickerRow(it, isPlayingRow(it, primaryId)) { onPick(it) } } }
            }
        }
    }
}

/** A row is the playing one only if both its playlist and its key match the channel the user chose: two playlists can hold the same key. */
internal fun isPlayingRow(item: ItemEntity, primaryId: String?) = primaryId == MediaIds.of(item.sourceId, item.stableKey)

@Composable
private fun Paged(channels: LazyPagingItems<ItemEntity>, primaryId: String, onPick: (ItemEntity) -> Unit) {
    if (channels.isLoadedEmpty) {
        EmptyState(stringResource(R.string.live_nothing_in_this_list), "")
        return
    }
    LazyColumn {
        items(channels.itemCount, key = channels.itemKey { it.id }) { i ->
            val ch = channels[i] ?: return@items // placeholder while the page loads
            PickerRow(ch, isPlayingRow(ch, primaryId)) { onPick(ch) }
        }
    }
}

@Composable
private fun PickerRow(ch: ItemEntity, playing: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (playing) R.string.player_channels_playing else R.string.live_play, ch.name)
    Row(
        Modifier.fillMaxWidth().pressable(onClick, label = label).padding(horizontal = Mux.ScreenPadding, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(ch.logo, ch.name, Modifier.size(width = 64.dp, height = 46.dp).clip(Mux.CardShape).padding(4.dp), contentScale = ContentScale.Fit, monogramSize = 14)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = MaterialTheme.typography.titleMedium, color = if (playing) MuxTheme.Primary else MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(ch.number?.toString(), ch.groupTitle.ifEmpty { null }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) Icon(Icons.Rounded.PlayArrow, null, tint = MuxTheme.Primary)
    }
}
