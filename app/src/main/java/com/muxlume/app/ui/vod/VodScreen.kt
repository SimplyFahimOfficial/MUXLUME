package com.muxlume.app.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.PosterCard
import com.muxlume.app.ui.live.BrowseFilters
import com.muxlume.app.ui.live.BrowseViewModel
import com.muxlume.app.ui.live.GroupFilter
import com.muxlume.app.ui.live.isLoadedEmpty
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.muxlume.app.ui.main.BottomBarSpace
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux

/** Movies or series of the selected source as a poster grid. */
@Composable
fun VodScreen(kind: ItemKind, nav: Nav) {
    val context = LocalContext.current
    val vm: BrowseViewModel = viewModel(key = kind.name) { BrowseViewModel(context.container, kind) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val items = vm.items.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    LaunchedEffect(browse, sourceId) { gridState.scrollToItem(0) }
    val title = if (kind == ItemKind.MOVIE) "Movies" else "Series"

    Column(Modifier.fillMaxSize()) {
        TopHeader(title, onSearch = nav::search)
        val list = sources ?: return@Column
        if (list.isEmpty()) {
            EmptyState(
                "No ${title.lowercase()} yet",
                if (kind == ItemKind.SERIES) "Series come from Xtream Codes-compatible accounts that include them."
                else "Movies come from Xtream Codes-compatible accounts, or from playlist entries that point to video files.",
                action = "Add a source", onAction = { nav.addSource() },
            )
            return@Column
        }
        BrowseFilters(vm, list, sourceId, browse, groups)
        if (items.isLoadedEmpty) {
            EmptyState(
                if (browse.filter is GroupFilter.Favorites) "Your watchlist is empty" else "Nothing here",
                if (browse.filter is GroupFilter.Favorites) "Add titles to your watchlist from their detail page." else "This source has no ${title.lowercase()} in this category.",
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            state = gridState,
            contentPadding = PaddingValues(start = Mux.ScreenPadding, end = Mux.ScreenPadding, top = 12.dp, bottom = BottomBarSpace + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items.itemCount, key = items.itemKey { it.id }) { i ->
                val item = items[i] ?: return@items // placeholder while the page loads
                PosterCard(item.name, item.logo, { nav.detail(item.sourceId, item.stableKey) }, subtitle = item.year, width = null)
            }
        }
    }
}
