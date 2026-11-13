package com.muxlume.app.ui.live

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.container
import com.muxlume.app.data.db.FavoriteEntity
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.PillRow
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.guide.NowNextLine
import com.muxlume.app.ui.guide.rememberNowNext
import com.muxlume.app.ui.main.BottomBarSpace
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.ui.playChannel
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.Flow
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Group filter: all channels, favorites, or one provider group. */
sealed interface GroupFilter {
    data object All : GroupFilter
    data object Favorites : GroupFilter
    data class Group(val title: String) : GroupFilter
}

data class Browse(val sourceId: Long?, val filter: GroupFilter, val alphabetical: Boolean)

/**
 * Shared by Live TV, Movies and Series: one source at a time, provider groups as pills,
 * favorites as a filter, provider order or A-Z.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(private val c: AppContainer, val kind: ItemKind) : ViewModel() {
    val browse = MutableStateFlow(Browse(null, GroupFilter.All, false))

    val sources: StateFlow<List<SourceEntity>?> = c.sources.sources
        .map { list -> list.filter { s -> s.lastError != null || s.refreshedAt == null || countOf(s) > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun countOf(s: SourceEntity) = when (kind) {
        ItemKind.LIVE -> s.liveCount
        ItemKind.MOVIE -> s.movieCount
        ItemKind.SERIES -> s.seriesCount
    }

    /** The selected source, or the first one with items of this kind. */
    val sourceId: StateFlow<Long?> = combine(sources, browse) { list, b ->
        list?.let { l -> b.sourceId?.takeIf { id -> l.any { it.id == id } } ?: (l.firstOrNull { countOf(it) > 0 } ?: l.firstOrNull())?.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val groups: StateFlow<List<String>> = sourceId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.db.items().groups(id, kind) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Loaded a page at a time, so groups with hundreds of thousands of channels stay light. */
    val items: Flow<PagingData<ItemEntity>> = combine(sourceId, browse) { id, b -> id to b }.flatMapLatest { (id, b) ->
        if (id == null) {
            flowOf(PagingData.empty())
        } else {
            Pager(PagingConfig(pageSize = PAGE_SIZE)) {
                if (b.filter is GroupFilter.Favorites) c.db.items().favoritesPage(kind, id)
                else c.db.items().page(id, kind, (b.filter as? GroupFilter.Group)?.title, b.alphabetical)
            }.flow
        }
    }.cachedIn(viewModelScope)

    /** The list as shown, so channel up/down in the player follows it. */
    fun channelList(): ChannelList = browse.value.let { b ->
        when (val f = b.filter) {
            GroupFilter.Favorites -> ChannelList(ChannelList.Order.FAVORITES, group = null, favoritesSourceId = sourceId.value)
            else -> ChannelList(
                if (b.alphabetical) ChannelList.Order.NAME else ChannelList.Order.PROVIDER,
                group = (f as? GroupFilter.Group)?.title,
            )
        }
    }

    val favorites: StateFlow<Set<String>> = sourceId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.db.favorites().keys(id) }
        .map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun selectSource(id: Long) = browse.value.let { browse.value = Browse(id, GroupFilter.All, it.alphabetical) }
    fun filter(f: GroupFilter) { browse.value = browse.value.copy(filter = f) }
    fun toggleSort() { browse.value = browse.value.copy(alphabetical = !browse.value.alphabetical) }

    fun toggleFavorite(item: ItemEntity, isFavorite: Boolean) = viewModelScope.launch {
        if (isFavorite) c.db.favorites().remove(item.sourceId, item.stableKey)
        else c.db.favorites().add(FavoriteEntity(item.sourceId, item.stableKey, System.currentTimeMillis()))
    }

    private companion object {
        const val PAGE_SIZE = 60
    }
}

/** True once the first page has loaded and the list is empty (not while it's still loading). */
val LazyPagingItems<*>.isLoadedEmpty: Boolean get() = itemCount == 0 && loadState.refresh is LoadState.NotLoading

@Composable
fun LiveScreen(nav: Nav) {
    val context = LocalContext.current
    val vm: BrowseViewModel = viewModel(key = "live") { BrowseViewModel(context.container, ItemKind.LIVE) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val channels = vm.items.collectAsLazyPagingItems()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val guide = sources?.firstOrNull { it.id == sourceId }?.guideEnabled == true
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var options by remember { mutableStateOf<ItemEntity?>(null) }
    LaunchedEffect(browse, sourceId) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TopHeader(
            stringResource(R.string.live_live_tv), onSearch = nav::search,
            actions = { IconButton(nav::guide) { Icon(Icons.Rounded.CalendarMonth, stringResource(R.string.guide_title), tint = MuxTheme.TextPrimary) } },
        )
        val list = sources ?: return@Column
        if (list.isEmpty()) {
            EmptyState(
                stringResource(R.string.live_no_channels_yet),
                stringResource(R.string.live_add_an_m3u_playlist_or),
                action = stringResource(R.string.live_add_a_playlist),
                onAction = { nav.addSource() },
            )
            return@Column
        }
        BrowseFilters(vm, list, sourceId, browse, groups)
        if (channels.isLoadedEmpty) {
            EmptyState(
                stringResource(if (browse.filter is GroupFilter.Favorites) R.string.live_no_favorites_here else R.string.live_nothing_in_this_list),
                stringResource(if (browse.filter is GroupFilter.Favorites) R.string.live_tap_the_star_next_to else R.string.live_this_playlist_has_no_live),
            )
            return@Column
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = BottomBarSpace + 16.dp, top = 8.dp)) {
            items(channels.itemCount, key = channels.itemKey { it.id }) { i ->
                val ch = channels[i] ?: return@items // placeholder while the page loads
                val fav = ch.stableKey in favorites
                ChannelRow(
                    ch, fav, guide,
                    onClick = { scope.launch { if (context.playChannel(ch, vm.channelList())) nav.player() } },
                    onFavorite = { vm.toggleFavorite(ch, fav) },
                    onOptions = { options = ch },
                )
            }
        }
        options?.let { ChannelOptionsSheet(it) { options = null } }
    }
}

@Composable
fun BrowseFilters(vm: BrowseViewModel, sources: List<SourceEntity>, sourceId: Long?, browse: Browse, groups: List<String>) {
    if (sources.size > 1) {
        PillRow(sources.map { it.name }, sources.indexOfFirst { it.id == sourceId }, { vm.selectSource(sources[it].id) })
        Spacer(Modifier.height(8.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val labels = listOf(stringResource(R.string.live_all), stringResource(R.string.live_favorites)) + groups.map { it.ifEmpty { stringResource(R.string.live_ungrouped) } }
        val selected = when (val f = browse.filter) {
            GroupFilter.All -> 0
            GroupFilter.Favorites -> 1
            is GroupFilter.Group -> groups.indexOf(f.title).let { if (it < 0) 0 else it + 2 }
        }
        PillRow(labels, selected, { i -> vm.filter(if (i == 0) GroupFilter.All else if (i == 1) GroupFilter.Favorites else GroupFilter.Group(groups[i - 2])) }, Modifier.weight(1f))
        IconButton(vm::toggleSort) {
            Icon(
                Icons.Rounded.SortByAlpha,
                stringResource(if (browse.alphabetical) R.string.live_sort_by_provider_order else R.string.live_sort_a_to_z),
                tint = if (browse.alphabetical) MuxTheme.Accent else MuxTheme.TextSecondary,
            )
        }
    }
}

@Composable
private fun ChannelRow(ch: ItemEntity, favorite: Boolean, guide: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onOptions: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .pressable(onClick, label = stringResource(R.string.live_play, ch.name), onLongClick = onOptions, longClickLabel = stringResource(R.string.channel_options))
            .padding(horizontal = Mux.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(ch.logo, ch.name, Modifier.size(width = 72.dp, height = 52.dp).clip(Mux.CardShape).background(MuxTheme.Raised).padding(6.dp), contentScale = ContentScale.Fit, monogramSize = 16)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(ch.number?.toString(), ch.groupTitle.ifEmpty { null }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            NowNextLine(rememberNowNext(ch.sourceId, ch.stableKey, guide).value)
        }
        IconButton(onFavorite) {
            Icon(
                if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (favorite) stringResource(R.string.live_remove_from_favorites, ch.name) else stringResource(R.string.live_add_to_favorites, ch.name),
                tint = if (favorite) MuxTheme.Accent else MuxTheme.TextSecondary,
            )
        }
    }
}
