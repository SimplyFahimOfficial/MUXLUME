package com.muxlume.app.ui.home

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.container
import com.muxlume.app.data.db.HistoryEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.playback.MediaIds
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.LiveBadge
import com.muxlume.app.ui.components.PosterCard
import com.muxlume.app.ui.components.PrimaryButton
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.WideCard
import com.muxlume.app.ui.main.BottomBarSpace
import com.muxlume.app.ui.main.Tab
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.play
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.ui.playChannel
import com.muxlume.app.ui.theme.OnMedia
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val loaded: Boolean = false,
    val sources: List<SourceEntity> = emptyList(),
    val continueWatching: List<HistoryEntity> = emptyList(),
    val recentChannels: List<HistoryEntity> = emptyList(),
    val favoriteChannels: List<ItemEntity> = emptyList(),
    val newMovies: List<ItemEntity> = emptyList(),
    val newSeries: List<ItemEntity> = emptyList(),
)

class HomeViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<HomeState> = combine(
        c.sources.sources,
        c.db.history().continueWatching(12),
        c.db.history().recentChannels(12),
        c.db.items().favorites(ItemKind.LIVE, null),
        combine(c.db.items().recentlyAdded(ItemKind.MOVIE, 16), c.db.items().recentlyAdded(ItemKind.SERIES, 16)) { m, s -> m to s },
    ) { sources, cw, rc, fav, (movies, series) -> HomeState(true, sources, cw, rc, fav, movies, series) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

/** Only sections with real data are shown; an empty install gets one clear call to action. */
@Composable
fun HomeScreen(nav: Nav, openTab: (Tab) -> Unit) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel { HomeViewModel(context.container) }
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun playEntry(e: HistoryEntity) = scope.launch { if (context.play(listOf(MediaIds.request(e)))) nav.player() }
    // Favorites across all sources, newest first, as the row shows them.
    fun playFavorite(ch: ItemEntity) = scope.launch {
        if (context.playChannel(ch, ChannelList(ChannelList.Order.FAVORITES, group = null))) nav.player()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomBarSpace + 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { TopHeader(null, onSearch = nav::search) }
        if (!s.loaded) return@LazyColumn
        if (s.sources.isEmpty()) {
            item {
                EmptyState(
                    stringResource(R.string.home_bring_your_own_playlist),
                    stringResource(R.string.home_muxlume_plays_the_m3u_playlists),
                    action = stringResource(R.string.home_add_a_playlist), onAction = { nav.addSource() },
                )
            }
            return@LazyColumn
        }
        val hero = s.continueWatching.firstOrNull()
        if (hero != null) {
            item {
                Hero(
                    title = hero.title, subtitle = hero.subtitle, artwork = hero.artwork, live = false,
                    progress = if (hero.durationMs > 0) hero.positionMs.toFloat() / hero.durationMs else null,
                    action = stringResource(R.string.home_resume), onPlay = { playEntry(hero) },
                )
            }
        } else if (s.favoriteChannels.isNotEmpty()) {
            val ch = s.favoriteChannels.first()
            item { Hero(ch.name, ch.groupTitle, ch.logo, live = true, progress = null, action = stringResource(R.string.home_watch_live), onPlay = { playFavorite(ch) }, fitArt = true) }
        }
        if (s.continueWatching.size > 1) {
            item {
                Shelf(stringResource(R.string.home_continue_watching), s.continueWatching.drop(1)) { e ->
                    WideCard(e.title, e.subtitle, e.artwork, { playEntry(e) }, progress = if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else null)
                }
            }
        }
        if (s.favoriteChannels.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.home_favorite_channels), s.favoriteChannels, action = stringResource(R.string.home_all_channels), onAction = { openTab(Tab.LIVE) }) { ch ->
                    WideCard(ch.name, ch.groupTitle.ifEmpty { null }, ch.logo, { playFavorite(ch) }, fit = true)
                }
            }
        }
        if (s.recentChannels.isNotEmpty()) {
            item { Shelf(stringResource(R.string.home_recently_watched), s.recentChannels) { e -> WideCard(e.title, e.subtitle, e.artwork, { playEntry(e) }, fit = true) } }
        }
        if (s.newMovies.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.home_recently_added_movies), s.newMovies, action = stringResource(R.string.home_movies), onAction = { openTab(Tab.MOVIES) }) { m ->
                    PosterCard(m.name, m.logo, { nav.detail(m.sourceId, m.stableKey) }, subtitle = m.year)
                }
            }
        }
        if (s.newSeries.isNotEmpty()) {
            item {
                Shelf(stringResource(R.string.home_new_series), s.newSeries, action = stringResource(R.string.home_series), onAction = { openTab(Tab.SERIES) }) { m ->
                    PosterCard(m.name, m.logo, { nav.detail(m.sourceId, m.stableKey) }, subtitle = m.year)
                }
            }
        }
        if (hero == null && s.favoriteChannels.isEmpty() && s.recentChannels.isEmpty()) {
            item {
                EmptyState(
                    stringResource(R.string.home_start_with_live_tv),
                    stringResource(R.string.home_pick_a_channel_and_it),
                    action = stringResource(R.string.home_browse_channels), onAction = { openTab(Tab.LIVE) },
                )
            }
        }
        item {
            SectionHeader(stringResource(R.string.home_your_playlists), action = stringResource(R.string.home_manage), onAction = { openTab(Tab.LIBRARY) })
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = Mux.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.sources, key = { it.id }) { src ->
                    Column(Modifier.widthIn(min = 160.dp).clip(Mux.CardShape).background(MuxTheme.Surface).padding(16.dp)) {
                        Text(src.name, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                src.liveCount.takeIf { it > 0 }?.let { stringResource(R.string.home_channels, it) },
                                src.movieCount.takeIf { it > 0 }?.let { stringResource(R.string.home_movies_2, it) },
                                src.seriesCount.takeIf { it > 0 }?.let { stringResource(R.string.home_series_2, it) },
                            ).joinToString(" · ").ifEmpty { stringResource(if (src.lastError != null) R.string.home_import_failed else R.string.home_not_imported_yet) },
                            style = MaterialTheme.typography.bodySmall, color = if (src.lastError != null) MuxTheme.Error else MuxTheme.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Shelf(title: String, items: List<T>, action: String? = null, onAction: (() -> Unit)? = null, card: @Composable (T) -> Unit) {
    Column {
        SectionHeader(title, action = action, onAction = onAction)
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = Mux.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items) { card(it) }
        }
    }
}

/** The cinematic lead card: artwork edge to edge, a fade only where text sits, one clear action. */
@Composable
private fun Hero(title: String, subtitle: String?, artwork: String?, live: Boolean, progress: Float?, action: String, onPlay: () -> Unit, fitArt: Boolean = false) {
    // Text sits on artwork, so force the dark palette here regardless of the app theme.
    OnMedia {
        Box(Modifier.padding(horizontal = Mux.ScreenPadding).fillMaxWidth().aspectRatio(4f / 5f).clip(Mux.HeroShape).background(MuxTheme.Surface)) {
            Artwork(
                artwork, title,
                Modifier.fillMaxWidth().aspectRatio(if (fitArt) 1.3f else 4f / 5f).padding(if (fitArt) 48.dp else 0.dp),
                contentScale = if (fitArt) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                monogramSize = 64,
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to MuxTheme.Background)))
            if (live) LiveBadge(Modifier.align(Alignment.TopStart).padding(16.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                if (subtitle != null) Text(subtitle.uppercase(), style = com.muxlume.app.ui.theme.SectionLabel, color = MuxTheme.Primary.copy(alpha = 0.8f), maxLines = 1)
                Text(title, style = MaterialTheme.typography.displaySmall, color = MuxTheme.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (progress != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(0.5f).height(4.dp).clip(Mux.PillShape).background(MuxTheme.Outline)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(MuxTheme.Accent))
                    }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(action, onPlay, icon = Icons.Rounded.PlayArrow)
            }
        }
    }
}
