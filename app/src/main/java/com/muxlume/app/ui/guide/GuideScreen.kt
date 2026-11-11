package com.muxlume.app.ui.guide

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.muxlume.app.AppContainer
import com.muxlume.app.R
import com.muxlume.app.container
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.ProgramEntity
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.epg.EpgTime
import com.muxlume.app.playback.ChannelList
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.Artwork
import com.muxlume.app.ui.components.EmptyState
import com.muxlume.app.ui.components.PillRow
import com.muxlume.app.ui.components.pressable
import com.muxlume.app.ui.live.isLoadedEmpty
import com.muxlume.app.ui.main.TopHeader
import com.muxlume.app.ui.playChannel
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

private val HOUR_WIDTH = 180.dp
private val NAME_WIDTH = 104.dp
private val ROW_HEIGHT = 68.dp
private val RULER_HEIGHT = 32.dp
private const val DAYS = 7
private const val SECONDS_PER_HOUR = 3600f
private val STALE_AFTER_MILLIS = TimeUnit.HOURS.toMillis(36)

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(private val c: AppContainer) : ViewModel() {
    val sources: StateFlow<List<SourceEntity>?> = c.sources.sources.map { all -> all.filter { it.guideEnabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val picked = MutableStateFlow<Long?>(null)

    /** The chosen source, or the first one with its guide on. */
    val sourceId: StateFlow<Long?> = combine(sources, picked) { list, p -> list?.let { l -> l.firstOrNull { it.id == p }?.id ?: l.firstOrNull()?.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val group = MutableStateFlow<String?>(null)
    val day = MutableStateFlow(0)

    val groups: StateFlow<List<String>> = sourceId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.db.items().groups(id, ItemKind.LIVE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live channels that have guide data, a page at a time. */
    val channels: Flow<PagingData<ItemEntity>> = combine(sourceId, group) { id, g -> id to g }.flatMapLatest { (id, g) ->
        if (id == null) flowOf(PagingData.empty()) else Pager(PagingConfig(pageSize = PAGE_SIZE)) { c.db.epg().linkedPage(id, g) }.flow
    }.cachedIn(viewModelScope)

    fun pick(id: Long) { picked.value = id; group.value = null }

    suspend fun programs(item: ItemEntity, from: Long, to: Long): List<ProgramEntity> = c.epg.programs(item.sourceId, item.stableKey, from, to)

    private companion object {
        const val PAGE_SIZE = 40
    }
}

/** Channels down the side, time across: one shared horizontal scroll for the ruler and every row. */
@Composable
fun GuideScreen(nav: Nav) {
    val context = LocalContext.current
    val vm: GuideViewModel = viewModel { GuideViewModel(context.container) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopHeader(
            stringResource(R.string.guide_title), onSearch = null, onBack = nav::back,
            actions = {
                IconButton(nav::guideSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.guide_settings_title), tint = MuxTheme.TextPrimary) }
            },
        )
        val list = sources ?: return@Column
        if (list.isEmpty()) {
            EmptyState(
                stringResource(R.string.guide_none_title), stringResource(R.string.guide_none_body),
                action = stringResource(R.string.guide_none_action), onAction = nav::guideSettings,
            )
            return@Column
        }
        val source = list.firstOrNull { it.id == sourceId }
        if (list.size > 1) PillRow(list.map { it.name }, list.indexOfFirst { it.id == sourceId }, { vm.pick(list[it].id) })

        val zone = remember { ZoneId.systemDefault() }
        val today = remember { LocalDate.now(zone) }
        PillRow(dayLabels(today), day, { vm.day.value = it })
        val labels = listOf(stringResource(R.string.live_all)) + groups.map { it.ifEmpty { stringResource(R.string.live_ungrouped) } }
        PillRow(labels, groups.indexOf(group).let { if (it < 0) 0 else it + 1 }, { i -> vm.group.value = if (i == 0) null else groups[i - 1] })

        source?.epgRefreshedAt?.takeIf { System.currentTimeMillis() - it > STALE_AFTER_MILLIS }?.let { at ->
            Text(
                stringResource(R.string.guide_stale, DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)),
                style = MaterialTheme.typography.bodySmall, color = MuxTheme.Error,
                modifier = Modifier.padding(horizontal = Mux.ScreenPadding, vertical = 6.dp),
            )
        }

        if (channels.isLoadedEmpty) {
            EmptyState(
                stringResource(R.string.guide_empty_title), stringResource(R.string.guide_empty_body),
                action = stringResource(R.string.guide_match_channels), onAction = { sourceId?.let(nav::guideMapping) },
            )
            return@Column
        }

        val (dayStart, dayEnd) = remember(day, today) { EpgTime.dayWindow(today, day, zone) }
        val dayWidth = HOUR_WIDTH * ((dayEnd - dayStart) / SECONDS_PER_HOUR)
        val scroll = rememberScrollState()
        val hourPx = with(LocalDensity.current) { HOUR_WIDTH.toPx() }
        val nowSeconds by produceState(System.currentTimeMillis() / MILLIS) {
            while (true) { delay(TimeUnit.MINUTES.toMillis(1)); value = System.currentTimeMillis() / MILLIS }
        }
        // Start half an hour before now today, or in the morning on other days (once the row widths are known).
        LaunchedEffect(day, sourceId) {
            snapshotFlow { scroll.maxValue }.first { it > 0 }
            val hours = if (day == 0) ((nowSeconds - dayStart) / SECONDS_PER_HOUR - 0.5f).coerceAtLeast(0f) else MORNING_HOUR
            scroll.scrollTo((hours * hourPx).toInt())
        }
        var selected by remember { mutableStateOf<Pair<ItemEntity, ProgramEntity>?>(null) }

        Ruler(dayStart, dayEnd, dayWidth, scroll)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(channels.itemCount, key = channels.itemKey { it.id }) { i ->
                val item = channels[i] ?: return@items // placeholder while the page loads
                val programs by produceState<List<ProgramEntity>?>(null, item.id, dayStart, source?.epgRefreshedAt) {
                    value = vm.programs(item, dayStart, dayEnd)
                }
                ChannelRow(
                    item, programs, dayStart, dayEnd, dayWidth, scroll, nowSeconds,
                    onChannel = { scope.launch { if (context.playChannel(item, ChannelList(ChannelList.Order.PROVIDER, group))) nav.player() } },
                    onProgram = { selected = item to it },
                )
            }
        }
        selected?.let { (item, program) ->
            ProgramSheet(
                item, program,
                onWatch = { scope.launch { selected = null; if (context.playChannel(item, ChannelList(ChannelList.Order.PROVIDER, group))) nav.player() } },
                onDismiss = { selected = null },
            )
        }
    }
}

@Composable
private fun dayLabels(today: LocalDate): List<String> = (0 until DAYS).map { i ->
    when (i) {
        0 -> stringResource(R.string.guide_today)
        1 -> stringResource(R.string.guide_tomorrow)
        else -> today.plusDays(i.toLong()).let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.dayOfMonth}" }
    }
}

/** Hour marks across the top, in local clock time (so a daylight-saving day skips or repeats an hour). */
@Composable
private fun Ruler(dayStart: Long, dayEnd: Long, dayWidth: Dp, scroll: androidx.compose.foundation.ScrollState) {
    val hours = ((dayEnd - dayStart) / SECONDS_PER_HOUR).toInt()
    Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        Spacer(Modifier.width(NAME_WIDTH))
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(dayWidth).fillMaxHeight()) {
                for (h in 0 until hours) {
                    Text(
                        EpgTime.time(dayStart + h * 3600L), style = MaterialTheme.typography.labelSmall, color = MuxTheme.TextSecondary,
                        modifier = Modifier.offset(x = HOUR_WIDTH * h + 6.dp).align(Alignment.CenterStart),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    item: ItemEntity,
    programs: List<ProgramEntity>?,
    dayStart: Long,
    dayEnd: Long,
    dayWidth: Dp,
    scroll: androidx.compose.foundation.ScrollState,
    nowSeconds: Long,
    onChannel: () -> Unit,
    onProgram: (ProgramEntity) -> Unit,
) {
    val accent = MuxTheme.Accent
    val hourPx = with(LocalDensity.current) { HOUR_WIDTH.toPx() }
    val nowLine = with(LocalDensity.current) { 2.dp.toPx() }
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Row(
            Modifier.width(NAME_WIDTH).fillMaxHeight().pressable(onChannel, label = stringResource(R.string.live_play, item.name)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(item.logo, item.name, Modifier.size(32.dp).clip(Mux.CardShape).background(MuxTheme.Raised).padding(3.dp), contentScale = ContentScale.Fit, monogramSize = 10)
            Spacer(Modifier.width(6.dp))
            Text(item.name, style = MaterialTheme.typography.labelSmall, color = MuxTheme.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(
                Modifier.width(dayWidth).fillMaxHeight().drawBehind {
                    if (nowSeconds in dayStart until dayEnd) {
                        drawRect(accent, Offset(hourPx * (nowSeconds - dayStart) / SECONDS_PER_HOUR, 0f), Size(nowLine, size.height))
                    }
                },
            ) {
                programs?.forEach { p -> ProgramBlock(p, dayStart, dayEnd, nowSeconds) { onProgram(p) } }
            }
        }
    }
}

@Composable
private fun ProgramBlock(p: ProgramEntity, dayStart: Long, dayEnd: Long, nowSeconds: Long, onClick: () -> Unit) {
    val from = maxOf(p.start, dayStart)
    val to = minOf(p.stop, dayEnd)
    val x = HOUR_WIDTH * ((from - dayStart) / SECONDS_PER_HOUR)
    val w = HOUR_WIDTH * ((to - from) / SECONDS_PER_HOUR)
    val live = nowSeconds in p.start until p.stop
    val past = p.stop <= nowSeconds
    val range = EpgTime.range(p.start, p.stop)
    Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(2.dp)) {
        Column(
            Modifier.fillMaxSize().clip(Mux.CardShape)
                .background(if (live) MuxTheme.Accent.copy(alpha = ACTIVE_TINT) else MuxTheme.Raised)
                .then(if (live) Modifier.border(1.dp, MuxTheme.Accent.copy(alpha = ACTIVE_BORDER), Mux.CardShape) else Modifier)
                .pressable(onClick, label = p.title)
                .semantics(mergeDescendants = true) { contentDescription = "${p.title}, $range" }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (w > MIN_TEXT_WIDTH) {
                Text(p.title, style = MaterialTheme.typography.labelLarge, color = if (past) MuxTheme.TextSecondary else MuxTheme.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(range, style = MaterialTheme.typography.labelSmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

private const val MILLIS = 1000L
private const val MORNING_HOUR = 6f
private const val ACTIVE_TINT = 0.16f
private const val ACTIVE_BORDER = 0.6f
private val MIN_TEXT_WIDTH = 36.dp
