package com.muxlume.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.playback.AlternativeFinder
import com.muxlume.app.playback.ChannelNames.Tier
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.launch

/** One line of the list; [button] is "Add as server" or "Remove" for rows that have one. */
private class Line(val item: ItemEntity, val playlist: String, val button: Pair<Int, () -> Unit>? = null)

/**
 * The servers of the channel the user chose ([primaryId]): the channel itself, the ones they saved, and the ones found by name
 * in their playlists, "Same name" before "Similar name". Tapping a row plays it. It never shows a stream URL.
 */
@Composable
fun ServersSheet(primaryId: String, playingId: String?, onPlay: (ItemEntity) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val unlocked by c.lock.unlocked.collectAsStateWithLifecycle()
    val (sourceId, key) = MediaIds.parse(primaryId) ?: return
    val playlists by c.db.sources().all().collectAsStateWithLifecycle(emptyList())
    val saved by c.db.channelServers().forChannel(sourceId, key, unlocked).collectAsStateWithLifecycle(emptyList())
    val primary by produceState<ItemEntity?>(null, primaryId, unlocked) { value = c.db.items().byKey(sourceId, key, unlocked) }
    val found by produceState<List<AlternativeFinder.Alternative>?>(null, primary, unlocked) { value = primary?.let { AlternativeFinder(c.db).find(it, unlocked) } }
    fun later(block: suspend () -> Unit): () -> Unit = { scope.launch { block() } }
    fun nameOf(id: Long) = playlists.firstOrNull { it.id == id }?.name.orEmpty()

    val savedKeys = saved.map { MediaIds.of(it.item.sourceId, it.item.stableKey) }.toSet()
    val own = primary?.let { Line(it, nameOf(it.sourceId)) }
    val savedLines = saved.map { s ->
        Line(s.item, nameOf(s.item.sourceId), R.string.player_server_remove to later { c.db.channelServers().remove(sourceId, key, s.item.sourceId, s.item.stableKey) })
    }

    fun lines(tier: Tier) = found.orEmpty().filter { it.tier == tier && MediaIds.of(it.item.sourceId, it.item.stableKey) !in savedKeys }.map { a ->
        Line(a.item, a.sourceName, R.string.player_server_add to later { c.db.channelServers().add(sourceId, key, a.item.sourceId, a.item.stableKey) })
    }
    val same = lines(Tier.SAME)
    val similar = lines(Tier.SIMILAR)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuxTheme.Surface) {
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            section(R.string.player_servers_channel, listOfNotNull(own), playingId, onPlay)
            section(R.string.player_servers_saved, savedLines, playingId, onPlay)
            section(R.string.player_servers_same, same, playingId, onPlay)
            section(R.string.player_servers_similar, similar, playingId, onPlay)
            item {
                when {
                    found == null -> CircularProgressIndicator(Modifier.padding(Mux.ScreenPadding), color = MuxTheme.Primary)
                    same.isEmpty() && similar.isEmpty() && savedLines.isEmpty() ->
                        Text(stringResource(R.string.player_servers_none), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary, modifier = Modifier.padding(Mux.ScreenPadding))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: Int, lines: List<Line>, playingId: String?, onPlay: (ItemEntity) -> Unit) {
    if (lines.isEmpty()) return
    item(key = "title$title") {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MuxTheme.TextSecondary, modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 8.dp))
    }
    items(lines.size, key = { "$title|${lines[it].item.id}" }) { i ->
        val line = lines[i]
        val playing = MediaIds.of(line.item.sourceId, line.item.stableKey) == playingId
        val description = listOf(line.item.name, line.playlist, line.item.groupTitle).filter { it.isNotEmpty() }.joinToString(", ")
        val label = stringResource(if (playing) R.string.player_channels_playing else R.string.live_play, description)
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(
                Modifier.weight(1f).pressable({ onPlay(line.item) }, label = label)
                    .padding(horizontal = Mux.ScreenPadding, vertical = 10.dp).semantics(mergeDescendants = true) { contentDescription = label },
            ) {
                Text(line.item.name, style = MaterialTheme.typography.titleMedium, color = if (playing) MuxTheme.Primary else MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(line.playlist, line.item.groupTitle).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            line.button?.let { (text, action) ->
                val buttonLabel = stringResource(text) + ", " + line.item.name
                TextButton(action, Modifier.semantics { contentDescription = buttonLabel }) { Text(stringResource(text), color = MuxTheme.Primary) }
            }
        }
    }
}
