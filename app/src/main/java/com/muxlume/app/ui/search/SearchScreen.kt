package com.muxlume.app.ui.search

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muxlume.app.AppContainer
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemDao
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.SectionHeader
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.playChannel
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val results = query.debounce(250).flatMapLatest { q ->
        val match = ItemDao.ftsQuery(q).takeIf { q.trim().length >= 2 }
        if (match == null) flowOf(null) else c.db.items().search(match, 300)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun SearchScreen(nav: Nav) {
    val context = LocalContext.current
    val vm: SearchViewModel = viewModel { SearchViewModel(context.container) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(MuxTheme.Background).imePadding()) {
        Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.search_back), tint = MuxTheme.TextPrimary) }
            TextField(
                query, { vm.query.value = it.take(100) },
                Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search_channels_movies_series)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MuxTheme.TextSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton({ vm.query.value = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear_search), tint = MuxTheme.TextSecondary) }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = Mux.PillShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MuxTheme.Raised, unfocusedContainerColor = MuxTheme.Raised, cursorColor = MuxTheme.Accent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
        val list = results
        when {
            list == null -> Text(
                stringResource(R.string.search_type_at_least_two_letters), style = MaterialTheme.typography.bodyMedium, color = MuxTheme.TextSecondary,
                modifier = Modifier.padding(Mux.ScreenPadding),
            )
            list.isEmpty() -> EmptyState(stringResource(R.string.search_no_matches), stringResource(R.string.search_nothing_in_your_playlists_matches, query.trim()))
            else -> {
                val byKind = list.groupBy { it.kind }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    for (kind in ItemKind.entries) {
                        val items = byKind[kind] ?: continue
                        item(key = kind.name) {
                            val title = when (kind) {
                                ItemKind.LIVE -> R.string.search_channels
                                ItemKind.MOVIE -> R.string.search_movies
                                ItemKind.SERIES -> R.string.search_series
                            }
                            SectionHeader(stringResource(title), Modifier.padding(top = 16.dp, bottom = 4.dp))
                        }
                        items(items, key = { it.id }) { item ->
                            ResultRow(item) {
                                if (kind == ItemKind.LIVE) scope.launch { if (context.playChannel(item)) nav.player() } // up/down: the channel's group
                                else nav.detail(item.sourceId, item.stableKey)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: ItemEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick, label = item.name).padding(horizontal = Mux.ScreenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val live = item.kind == ItemKind.LIVE
        Artwork(
            item.logo, item.name,
            Modifier.size(width = if (live) 64.dp else 44.dp, height = if (live) 46.dp else 64.dp).clip(Mux.CardShape).padding(if (live) 4.dp else 0.dp),
            contentScale = if (live) ContentScale.Fit else ContentScale.Crop, monogramSize = 14,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.groupTitle.isNotEmpty()) Text(item.groupTitle, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1)
        }
    }
}
