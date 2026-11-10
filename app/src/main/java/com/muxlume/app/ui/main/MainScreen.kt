package com.muxlume.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muxlume.app.ui.Nav
import com.muxlume.app.ui.components.MuxLogo
import com.muxlume.app.ui.home.HomeScreen
import com.muxlume.app.ui.library.LibraryScreen
import com.muxlume.app.ui.live.LiveScreen
import com.muxlume.app.ui.theme.Motion
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.vod.VodScreen
import com.muxlume.app.data.db.ItemKind

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    LIVE("Live TV", Icons.Rounded.LiveTv),
    MOVIES("Movies", Icons.Rounded.Movie),
    SERIES("Series", Icons.Rounded.VideoLibrary),
    LIBRARY("Library", Icons.Rounded.CollectionsBookmark),
}

/** The five primary sections: a floating bottom bar on phones, a rail on wide screens. */
@Composable
fun MainScreen(nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = tab != 0) { tab = 0 }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Crossfade(tab, modifier, animationSpec = Motion.spec(Motion.SHORT, MuxTheme.reducedMotion), label = "tab") { t ->
            holder.SaveableStateProvider(t) {
                when (Tab.entries[t]) {
                    Tab.HOME -> HomeScreen(nav, openTab = { tab = it.ordinal })
                    Tab.LIVE -> LiveScreen(nav)
                    Tab.MOVIES -> VodScreen(ItemKind.MOVIE, nav)
                    Tab.SERIES -> VodScreen(ItemKind.SERIES, nav)
                    Tab.LIBRARY -> LibraryScreen(nav)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Mux.Black)) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                Rail(tab) { tab = it }
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            content(Modifier.fillMaxSize())
            BottomBar(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
        }
    }
}

/** Height the bottom bar covers, for content padding of scrollable screens. */
val BottomBarSpace = 96.dp

@Composable
private fun BottomBar(selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val reduced = MuxTheme.reducedMotion
    BoxWithConstraints(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(68.dp)
            .clip(Mux.PillShape)
            .background(Mux.Raised)
            .border(1.dp, Mux.Outline, Mux.PillShape),
    ) {
        val itemWidth = maxWidth / Tab.entries.size
        // One indicator that slides between tabs instead of five that fade.
        val x by animateDpAsState(itemWidth * selected, Motion.spec(Motion.MEDIUM, reduced), label = "indicator")
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(itemWidth).fillMaxHeight().padding(6.dp).clip(Mux.PillShape).background(Mux.Cream))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            Tab.entries.forEachIndexed { i, t ->
                val color by animateColorAsState(if (i == selected) Mux.Black else Mux.TextSecondary, Motion.spec(Motion.SHORT, reduced), label = "tabColor")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(Mux.PillShape).selectable(i == selected, role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    Text(t.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Rail(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(92.dp).background(Mux.Surface).windowInsetsPadding(WindowInsets.safeDrawing).selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        MuxLogo(size = 40.dp)
        Spacer(Modifier.height(28.dp))
        Tab.entries.forEachIndexed { i, t ->
            val on = i == selected
            Column(
                Modifier.padding(vertical = 6.dp).clip(Mux.CardShape).background(if (on) Mux.Cream else Mux.Surface)
                    .selectable(on, role = Role.Tab) { onSelect(i) }.padding(horizontal = 10.dp, vertical = 10.dp).width(60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, null, tint = if (on) Mux.Black else Mux.TextSecondary)
                Text(t.label, color = if (on) Mux.Black else Mux.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
