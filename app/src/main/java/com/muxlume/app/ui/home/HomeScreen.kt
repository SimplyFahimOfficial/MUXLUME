package com.muxlume.app.ui.home

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
import com.muxlume.app.ui.playFromList
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
    fun playChannels(list: List<ItemEntity>, i: Int) = scope.launch { if (context.playFromList(list, i)) nav.player() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomBarSpace + 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { TopHeader(null, onSearch = nav::search) }
        if (!s.loaded) return@LazyColumn
        if (s.sources.isEmpty()) {
            item {
                EmptyState(
                    "Bring your own playlist",
                    "MUXLUME plays the M3U playlists and Xtream Codes-compatible services you already have access to. It doesn't include any channels or subscriptions.",
                    action = "Add a playlist", onAction = { nav.addSource() },
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
                    action = "Resume", onPlay = { playEntry(hero) },
                )
            }
        } else if (s.favoriteChannels.isNotEmpty()) {
            val ch = s.favoriteChannels.first()
            item { Hero(ch.name, ch.groupTitle, ch.logo, live = true, progress = null, action = "Watch live", onPlay = { playChannels(s.favoriteChannels, 0) }, fitArt = true) }
        }
        if (s.continueWatching.size > 1) {
            item {
                Shelf("Continue watching", s.continueWatching.drop(1)) { e ->
                    WideCard(e.title, e.subtitle, e.artwork, { playEntry(e) }, progress = if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else null)
                }
            }
        }
        if (s.favoriteChannels.isNotEmpty()) {
            item {
                Shelf("Favorite channels", s.favoriteChannels, action = "All channels", onAction = { openTab(Tab.LIVE) }) { ch ->
                    WideCard(ch.name, ch.groupTitle.ifEmpty { null }, ch.logo, { playChannels(s.favoriteChannels, s.favoriteChannels.indexOf(ch)) }, fit = true)
                }
            }
        }
        if (s.recentChannels.isNotEmpty()) {
            item { Shelf("Recently watched", s.recentChannels) { e -> WideCard(e.title, e.subtitle, e.artwork, { playEntry(e) }, fit = true) } }
        }
        if (s.newMovies.isNotEmpty()) {
            item {
                Shelf("Recently added movies", s.newMovies, action = "Movies", onAction = { openTab(Tab.MOVIES) }) { m ->
                    PosterCard(m.name, m.logo, { nav.detail(m.sourceId, m.stableKey) }, subtitle = m.year)
                }
            }
        }
        if (s.newSeries.isNotEmpty()) {
            item {
                Shelf("New series", s.newSeries, action = "Series", onAction = { openTab(Tab.SERIES) }) { m ->
                    PosterCard(m.name, m.logo, { nav.detail(m.sourceId, m.stableKey) }, subtitle = m.year)
                }
            }
        }
        if (hero == null && s.favoriteChannels.isEmpty() && s.recentChannels.isEmpty()) {
            item {
                EmptyState(
                    "Start with Live TV",
                    "Pick a channel and it shows up here. Star channels to keep them at the top.",
                    action = "Browse channels", onAction = { openTab(Tab.LIVE) },
                )
            }
        }
        item {
            SectionHeader("Your playlists", action = "Manage", onAction = { openTab(Tab.LIBRARY) })
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = Mux.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.sources, key = { it.id }) { src ->
                    Column(Modifier.widthIn(min = 160.dp).clip(Mux.CardShape).background(MuxTheme.Surface).padding(16.dp)) {
                        Text(src.name, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                src.liveCount.takeIf { it > 0 }?.let { "$it channels" },
                                src.movieCount.takeIf { it > 0 }?.let { "$it movies" },
                                src.seriesCount.takeIf { it > 0 }?.let { "$it series" },
                            ).joinToString(" · ").ifEmpty { if (src.lastError != null) "Import failed" else "Not imported yet" },
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
