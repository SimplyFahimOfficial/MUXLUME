package com.muxlume.app.ui

import android.content.ContentResolver
import android.os.Looper
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.MuxlumeApp
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.MuxlumeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Motion in the real routes (the whole app root with a seeded catalogue, no network): a poster opens its detail page and back
 * returns to the same place, also when tapped twice, backed out by the gesture (committed or cancelled), with reduced motion, and
 * when the poster or the page is gone. Rapid tab changes settle on the last tab with no earlier tab left behind.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MotionRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val db get() = ApplicationProvider.getApplicationContext<MuxlumeApp>().container.db
    private var source = 0L

    @Before
    fun seed() = runBlocking {
        source = db.sources().insert(SourceEntity(name = "Home", type = SourceType.M3U_URL, secret = "x", label = "https://iptv.example", allowHttp = false, createdAt = 0, refreshedAt = 1, liveCount = 3, movieCount = 41, seriesCount = 3))
        db.items().insertAll(List(40) { i -> item(ItemKind.MOVIE, "m$i", "Movie %02d".format(i), i) })
        db.items().insertAll(listOf(item(ItemKind.MOVIE, "zq", "Zed Quantum", -1).copy(addedAt = 500))) // first in the grid; "ZQ" is the only placeholder with those initials
        db.items().insertAll(List(3) { i -> item(ItemKind.SERIES, "s$i", "Show %02d".format(i), i) })
        db.items().insertAll(List(3) { i -> item(ItemKind.LIVE, "l$i", "Channel %02d".format(i), i) })
    }

    private fun item(kind: ItemKind, key: String, name: String, position: Int) =
        ItemEntity(sourceId = source, kind = kind, stableKey = key, name = name, groupTitle = "All", logo = null, url = "https://x/$key", position = position, addedAt = 100L - position)

    private fun start(reduceMotion: Boolean = false) {
        compose.setContent { MuxlumeTheme(reduceMotion = reduceMotion) { MuxlumeRoot() } }
        waitForTabs()
        tab("Movies").performClick()
        waitFor("Movie 00")
    }

    /** The root shows nothing until the settings and the catalogue have been read; on a slow machine that takes a while. */
    private fun waitForTabs() = compose.waitUntil(10_000) { compose.onAllNodes(hasText("Movies") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())
    private fun waitFor(text: String) = compose.waitUntil(10_000) { exists(text) }
    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun onDetail() = exists("Watchlist")
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /** Starts the back gesture and drags it part of the way. */
    private fun swipe() = compose.runOnUiThread {
        val dispatcher = compose.activity.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(BackEventCompat(0.1f, 0.5f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(0.1f, 0.5f, 0.4f, BackEventCompat.EDGE_LEFT))
    }

    @Test
    fun `a poster opens its page and back returns to the grid where it was`() {
        start()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(30)
        waitFor("Movie 30")
        compose.onNodeWithText("Movie 30").performClick()
        waitFor("Watchlist")
        compose.onNodeWithText("Play").assertIsDisplayed()
        back()
        waitFor("Movie 30") // the grid came back scrolled, with the same poster in it
        compose.waitUntil(10_000) { !onDetail() }
        compose.onNodeWithText("Movie 30").assertIsDisplayed()
        assertThat(exists("Movie 00")).isFalse()
    }

    @Test
    fun `the poster grows into the page header while the page comes in`() {
        start()
        val initials = { compose.onAllNodesWithText("ZQ", useUnmergedTree = true) } // the placeholder drawn in the poster, and later in the header
        val header = { compose.onAllNodes(hasText("ZQ") and hasAnyAncestor(hasTestTag("detail-hero")), useUnmergedTree = true) }
        compose.onNodeWithText("Zed Quantum").performClick()
        compose.mainClock.autoAdvance = false
        var frames = 0
        while (header().fetchSemanticsNodes().isEmpty() && frames++ < 200) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(10) } // the page's data loads in the background
        compose.mainClock.advanceTimeByFrame()
        val first = header().onFirst().fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { initials().fetchSemanticsNodes().size == 1 } // the grid screen is gone, the header stays
        val placed = header().onFirst().fetchSemanticsNode().boundsInRoot
        assertThat(first.height).isLessThan(placed.height * 0.8f) // on its first frame the header is a poster-sized thumbnail ...
        assertThat(first.center.x).isLessThan(placed.center.x - 100) // ... at the poster's place, not yet where the header ends up
    }

    @Test
    fun `tapping a poster twice opens one page`() {
        start()
        compose.mainClock.autoAdvance = false
        val poster = hasText("Movie 00") and hasClickAction() // the page's title says "Movie 00" too, once it has loaded
        compose.onNode(poster).performClick()
        compose.mainClock.advanceTimeBy(40)
        compose.onNode(poster).performClick() // the grid is still there while the page comes in
        compose.mainClock.autoAdvance = true
        waitFor("Watchlist")
        back()
        compose.waitUntil(10_000) { !onDetail() }
        compose.onNodeWithText("Movie 00").assertIsDisplayed() // one back press was enough
    }

    @Test
    fun `the back gesture can be cancelled and then committed`() {
        start()
        compose.onNodeWithText("Movie 01").performClick()
        waitFor("Watchlist")
        compose.waitForIdle()
        swipe()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithText("Watchlist").assertIsDisplayed() // cancelled: still on the page, and it works
        compose.onNodeWithText("Watchlist").performClick()
        compose.waitUntil(10_000) { exists("In watchlist") }
        swipe()
        back() // committed
        compose.waitUntil(10_000) { !onDetail() }
        compose.onNodeWithText("Movie 01").assertIsDisplayed()
    }

    @Test
    fun `with reduced motion the page is there at once and nothing of the grid is left`() {
        start(reduceMotion = true)
        compose.onNodeWithText("Movie 02").performClick()
        compose.waitUntil(10_000) { onDetail() } // the page's own data loads in the background; the screens do not wait on any animation
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertThat(exists("Live TV")).isFalse() // the bottom bar of the grid screen is gone, not fading out
        back()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertThat(onDetail()).isFalse()
        assertThat(exists("Live TV")).isTrue()
        compose.onNodeWithText("Movie 02").assertIsDisplayed()
    }

    @Test
    fun `a page whose poster is gone from the grid still closes`() {
        start()
        compose.onNodeWithText("Movie 02").performClick()
        waitFor("Watchlist")
        runBlocking { db.channelPrefs().edit(source, "m2") { it.copy(hidden = true) } } // the item leaves the catalogue while its page is open
        compose.waitUntil(10_000) { !exists("Watchlist") }
        back()
        waitFor("Movie 03")
        assertThat(exists("Movie 02")).isFalse()
    }

    @Test
    fun `rapid tab changes settle on the last tab and leave no other tab behind`() {
        compose.setContent { MuxlumeTheme { MuxlumeRoot() } }
        waitForTabs()
        compose.mainClock.autoAdvance = false
        listOf("Movies", "Series", "Live TV", "Movies", "Series", "Movies").forEach {
            tab(it).performClick()
            compose.mainClock.advanceTimeBy(40) // each change lands while the last is still moving
        }
        compose.mainClock.autoAdvance = true
        waitFor("Movie 00")
        compose.waitForIdle()
        assertThat(exists("Show 00")).isFalse()
        assertThat(exists("Channel 00")).isFalse()
        tab("Movies").assertIsSelected()
    }

    @Test
    fun `the system animator setting is followed while the app is open`() {
        var reduced by mutableStateOf(false)
        compose.setContent { MuxlumeTheme { reduced = MuxTheme.reducedMotion; Text(if (MuxTheme.reducedMotion) "reduced" else "animated") } }
        compose.onNodeWithText("animated").assertIsDisplayed()
        val resolver: ContentResolver = compose.activity.contentResolver
        compose.runOnUiThread {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
            shadowOf(Looper.getMainLooper()).idle() // the observer's handler
        }
        compose.waitUntil(10_000) { reduced }
        compose.onNodeWithText("reduced").assertIsDisplayed()
        compose.runOnUiThread {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitUntil(10_000) { !reduced }
    }
}
