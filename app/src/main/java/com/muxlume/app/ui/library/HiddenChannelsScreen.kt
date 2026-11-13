package com.muxlume.app.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.muxlume.app.AppContainer
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.live.isLoadedEmpty
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HiddenChannelsViewModel(c: AppContainer) : ViewModel() {
    val channels = c.lock.unlocked.flatMapLatest { unlocked -> Pager(PagingConfig(pageSize = PAGE_SIZE)) { c.db.channelPrefs().hiddenPage(unlocked) }.flow }
        .cachedIn(viewModelScope)
    val count = c.lock.unlocked.flatMapLatest { c.db.channelPrefs().hiddenCount(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private companion object {
        const val PAGE_SIZE = 40
    }
}

/** Channels hidden with a long press, with a way back for each and for all of them. */
@Composable
fun HiddenChannelsScreen(nav: Nav) {
    val context = LocalContext.current
    val res = LocalResources.current
    val prefs = context.container.db.channelPrefs()
    val vm: HiddenChannelsViewModel = viewModel { HiddenChannelsViewModel(context.container) }
    val channels = vm.channels.collectAsLazyPagingItems()
    val count by vm.count.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        TopHeader(
            stringResource(R.string.hidden_title), onSearch = null, onBack = nav::back,
            actions = {
                if (count > 0) {
                    TextButton({
                        scope.launch {
                            prefs.restoreAll()
                            Toast.makeText(context, res.getString(R.string.hidden_restored), Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.hidden_restore_all), color = MuxTheme.Primary) }
                }
            },
        )
        if (channels.isLoadedEmpty) {
            EmptyState(stringResource(R.string.hidden_empty_title), stringResource(R.string.hidden_empty_body))
            return@Column
        }
        LazyColumn {
            items(channels.itemCount, key = channels.itemKey { it.id }) { i ->
                val ch = channels[i] ?: return@items // placeholder while the page loads
                HiddenRow(ch) { scope.launch { prefs.edit(ch.sourceId, ch.stableKey) { it.copy(hidden = false) } } }
            }
        }
    }
}

@Composable
private fun HiddenRow(ch: ItemEntity, onShow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Mux.ScreenPadding, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(ch.logo, ch.name, Modifier.size(width = 56.dp, height = 40.dp).clip(Mux.CardShape).padding(4.dp), contentScale = ContentScale.Fit, monogramSize = 14)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ch.groupTitle.isNotEmpty()) Text(ch.groupTitle, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val label = stringResource(R.string.hidden_show_named, ch.name)
        TextButton(onShow, Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.hidden_show), color = MuxTheme.Primary) }
    }
}
