package com.muxlume.app.ui.vod

import com.muxlume.app.R
import android.content.res.Resources
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.container
import com.muxlume.app.data.ErrorText
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.xtream.XtreamEpisode
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.components.PillRow
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SecondaryButton
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.play
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Extra provider metadata fetched when the page opens. Absent for M3U entries. */
data class Extra(
    val loading: Boolean = false,
    val error: String? = null,
    val plot: String? = null,
    val meta: List<String> = emptyList(),
    val backdrop: String? = null,
    val episodes: List<XtreamEpisode> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(private val c: AppContainer, private val res: Resources, private val sourceId: Long, private val key: String) : ViewModel() {
    val item: StateFlow<ItemEntity?> = c.db.items().observe(sourceId, key).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favorite = c.db.favorites().isFavorite(sourceId, key).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val extra = MutableStateFlow(Extra(loading = true))
    val season = MutableStateFlow<Int?>(null)

    /** Resume positions for the movie itself, or for the episodes of the series. */
    val history: StateFlow<Map<String, HistoryEntity>> = extra.flatMapLatest { e ->
        val ids = if (e.episodes.isEmpty()) listOf(MediaIds.of(sourceId, key)) else e.episodes.map { MediaIds.of(sourceId, MediaIds.episodeKey(it.id, it.extension)) }
        c.db.history().observe(ids.take(900))
    }.map { list -> list.associateBy { it.mediaId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init { load() }

    fun load() = viewModelScope.launch {
        extra.value = Extra(loading = true)
        val source = c.db.sources().get(sourceId)
        val it = c.db.items().byKey(sourceId, key)
        if (source?.type != SourceType.XTREAM || it?.remoteId == null) {
            extra.value = Extra(plot = it?.plot)
            return@launch
        }
        extra.value = try {
            withContext(Dispatchers.IO) {
                val api = c.sources.xtreamApi(source)
                if (it.kind == ItemKind.SERIES) {
                    val info = api.seriesInfo(it.remoteId)
                    Extra(
                        plot = info.plot ?: it.plot,
                        backdrop = info.cover,
                        episodes = info.episodes,
                        meta = listOfNotNull(
                            it.year,
                            it.rating?.let { r -> "★ $r" },
                            info.episodes.map { e -> e.season }.distinct().size.let { n -> res.getQuantityString(R.plurals.detail_seasons, n, n) }.takeIf { info.episodes.isNotEmpty() },
                        ),
                    )
                } else {
                    val info = api.vodInfo(it.remoteId)
                    Extra(
                        plot = info.plot ?: it.plot,
                        backdrop = info.backdrop,
                        meta = listOfNotNull(
                            info.releaseDate?.take(4) ?: it.year,
                            info.durationSec?.let { d -> res.getString(R.string.detail_min, d / 60) },
                            info.genre,
                            it.rating?.let { r -> "★ $r" },
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Extra(error = ErrorText.of(res, e), plot = it.plot, meta = listOfNotNull(it.year))
        }
    }

    fun toggleFavorite() = viewModelScope.launch {
        if (favorite.value) c.db.favorites().remove(sourceId, key) else c.db.favorites().add(FavoriteEntity(sourceId, key, System.currentTimeMillis()))
    }

    fun episodeRequest(series: ItemEntity, ep: XtreamEpisode) = MediaIds.request(
        MediaIds.of(sourceId, MediaIds.episodeKey(ep.id, ep.extension)),
        "S${ep.season} E${ep.number ?: "?"} · ${ep.title}", series.name, ep.image ?: series.logo, ItemKind.SERIES, parentKey = series.stableKey,
    )
}

@Composable
fun DetailScreen(sourceId: Long, key: String, nav: Nav) {
    val context = LocalContext.current
    val res = LocalResources.current
    val vm: DetailViewModel = viewModel(key = "$sourceId|$key") { DetailViewModel(context.container, context.applicationContext.resources, sourceId, key) }
    val item by vm.item.collectAsStateWithLifecycle()
    val extra by vm.extra.collectAsStateWithLifecycle()
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val selectedSeason by vm.season.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val it = item

    Box(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        if (it == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = MuxTheme.Accent)
        } else {
            val seasons = extra.episodes.map { e -> e.season }.distinct()
            val season = selectedSeason ?: seasons.firstOrNull()
            val episodes = extra.episodes.filter { e -> e.season == season }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.8f)) {
                        Artwork(extra.backdrop ?: it.logo, it.name, Modifier.fillMaxSize(), monogramSize = 72)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to MuxTheme.Background)))
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = Mux.ScreenPadding)) {
                        if (it.groupTitle.isNotEmpty()) Text(it.groupTitle.uppercase(), style = SectionLabel, color = MuxTheme.Accent)
                        Text(it.name, style = MaterialTheme.typography.displaySmall, color = MuxTheme.TextPrimary)
                        if (extra.meta.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(extra.meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary)
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (it.kind == ItemKind.MOVIE) {
                                val h = history[MediaIds.of(it.sourceId, it.stableKey)]
                                val resume = h != null && h.positionMs > 30_000 && (h.durationMs <= 0 || h.positionMs < h.durationMs * 0.95)
                                PrimaryButton(
                                    stringResource(if (resume) R.string.detail_resume else R.string.detail_play),
                                    { scope.launch { if (context.play(listOf(MediaIds.request(it)))) nav.player() } },
                                    icon = Icons.Rounded.PlayArrow,
                                )
                            } else if (episodes.isNotEmpty()) {
                                val next = nextEpisode(extra.episodes, history, it, res)
                                PrimaryButton(next.second, {
                                    scope.launch {
                                        val ep = next.first
                                        val list = extra.episodes.filter { e -> e.season == ep.season }
                                        if (context.play(list.map { e -> vm.episodeRequest(it, e) }, list.indexOf(ep))) nav.player()
                                    }
                                }, icon = Icons.Rounded.PlayArrow)
                            }
                            SecondaryButton(
                                stringResource(if (favorite) R.string.detail_in_watchlist else R.string.detail_watchlist),
                                vm::toggleFavorite,
                                icon = if (favorite) Icons.Rounded.Check else Icons.Rounded.Add,
                            )
                        }
                        extra.plot?.let { plot ->
                            Spacer(Modifier.height(18.dp))
                            Text(plot, style = MaterialTheme.typography.bodyLarge, color = MuxTheme.Primary.copy(alpha = 0.86f))
                        }
                        if (extra.loading) {
                            Spacer(Modifier.height(18.dp))
                            CircularProgressIndicator(Modifier.size(24.dp), color = MuxTheme.Accent, strokeWidth = 2.dp)
                        }
                        extra.error?.let { e ->
                            Spacer(Modifier.height(18.dp))
                            Text(stringResource(R.string.detail_details_couldn_t_be_loaded, e), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.Error)
                            Text(stringResource(R.string.detail_retry), color = MuxTheme.Primary, modifier = Modifier.clip(Mux.PillShape).pressable({ vm.load() }).padding(vertical = 10.dp))
                        }
                    }
                }
                if (seasons.size > 1) {
                    item {
                        Spacer(Modifier.height(24.dp))
                        PillRow(seasons.map { s -> stringResource(R.string.detail_season, s) }, seasons.indexOf(season), { i -> vm.season.value = seasons[i] })
                    }
                }
                if (episodes.isNotEmpty()) {
                    item { Spacer(Modifier.height(12.dp)) }
                    itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
                        val h = history[MediaIds.of(it.sourceId, MediaIds.episodeKey(ep.id, ep.extension))]
                        EpisodeRow(ep, h) { scope.launch { if (context.play(episodes.map { e -> vm.episodeRequest(it, e) }, i)) nav.player() } }
                    }
                }
            }
        }
        IconButton(
            nav::back,
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(8.dp).clip(Mux.PillShape).background(MuxTheme.Scrim),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back), tint = androidx.compose.ui.graphics.Color.White) } // on the dark scrim in any theme
    }
}

/** The first unfinished episode after the last one watched, or the first episode. */
private fun nextEpisode(all: List<XtreamEpisode>, history: Map<String, HistoryEntity>, series: ItemEntity, res: Resources): Pair<XtreamEpisode, String> {
    val idOf = { e: XtreamEpisode -> MediaIds.of(series.sourceId, MediaIds.episodeKey(e.id, e.extension)) }
    val first = all.first()
    val last = all.withIndex().filter { history[idOf(it.value)] != null }.maxByOrNull { history[idOf(it.value)]!!.updatedAt }
        ?: return first to res.getString(R.string.detail_play_s_e, first.season.toString(), (first.number ?: 1).toString())
    val h = history[idOf(last.value)]!!
    val finished = h.durationMs > 0 && h.positionMs >= h.durationMs * 0.95
    val ep = if (finished) all.getOrNull(last.index + 1) ?: last.value else last.value
    return ep to res.getString(if (ep == last.value && !finished) R.string.detail_resume_s_e else R.string.detail_play_s_e, ep.season.toString(), ep.number?.toString() ?: "?")
}

@Composable
private fun EpisodeRow(ep: XtreamEpisode, h: HistoryEntity?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, label = stringResource(R.string.detail_play_2, ep.title)).padding(horizontal = Mux.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(Mux.CardShape)) {
            Artwork(ep.image, ep.title, Modifier.fillMaxSize(), monogramSize = 18)
            if (h != null && h.durationMs > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(MuxTheme.Scrim)) {
                    Box(Modifier.fillMaxWidth((h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f)).height(3.dp).background(MuxTheme.Accent))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.detail_episode, ep.number ?: "?"), style = SectionLabel, color = MuxTheme.TextSecondary)
            Text(ep.title, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.durationSec?.takeIf { it > 0 }?.let { Text(stringResource(R.string.detail_min, it / 60), style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary) }
        }
    }
}
