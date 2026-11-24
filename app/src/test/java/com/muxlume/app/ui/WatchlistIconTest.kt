package com.muxlume.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.ui.components.watchlistPainter
import com.muxlume.app.ui.theme.MuxlumeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The watchlist icon draws a plus before and a tick after, from the bundled animated vector, or at once with reduced motion. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WatchlistIconTest {
    @get:Rule val compose = createComposeRule()
    private var added by mutableStateOf(false)

    private fun show(reduceMotion: Boolean, startAdded: Boolean = false) {
        added = startAdded
        setContent(reduceMotion)
    }

    private fun setContent(reduceMotion: Boolean) = compose.setContent {
        MuxlumeTheme(reduceMotion = reduceMotion) {
            Box(Modifier.size(24.dp).background(Color.White).testTag("icon")) { Icon(watchlistPainter(added), null, Modifier.size(24.dp), tint = Color.Black) }
        }
    }

    // Points on the 24x24 drawing, in dp: the top of the plus, and the first stroke of the tick (away from the plus).
    private fun inked(x: Float, y: Float): Boolean {
        val bitmap = compose.onNodeWithTag("icon").captureToImage().asAndroidBitmap()
        return android.graphics.Color.red(bitmap.getPixel((x * 3).toInt(), (y * 3).toInt())) < 128
    }

    private fun plus() = inked(12f, 6.5f)
    private fun tick() = inked(7.5f, 15f)
    private fun tickEnd() = inked(18.7f, 7.3f) // the far end of the tick, more than the plus's reach from the centre

    @Test
    fun `plus first, tick after, and a tick that is already added is drawn at once`() {
        show(reduceMotion = false)
        assertThat(plus() && !tick()).isTrue()
        added = true
        compose.waitForIdle()
        assertThat(!plus() && tick()).isTrue()
        added = false
        compose.waitForIdle()
        assertThat(plus() && !tick()).isTrue()
    }

    @Test
    fun `a title that is already added shows the tick on the first frame, not a plus drawing into one`() {
        show(reduceMotion = false, startAdded = true)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        assertThat(plus() to tick()).isEqualTo(false to true)
    }

    @Test
    fun `a change reversed while the tick is being drawn turns back instead of finishing it first`() {
        show(reduceMotion = false)
        compose.mainClock.autoAdvance = false
        added = true
        var frames = 0
        while (!tick() && frames++ < 60) compose.mainClock.advanceTimeBy(16) // until the tick has started
        assertThat(tick() && !tickEnd()).isTrue() // drawn part of the way
        added = false
        repeat(25) { // 400 ms: the tick's far end is never reached
            compose.mainClock.advanceTimeBy(16)
            assertThat(tickEnd()).isFalse()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(plus() && !tick()).isTrue() // and no half-drawn tick left over
    }

    @Test
    fun `with reduced motion the new state is there within a few frames`() {
        show(reduceMotion = true)
        assertThat(plus() to tick()).isEqualTo(true to false)
        val start = compose.mainClock.currentTime
        added = true
        compose.waitForIdle()
        assertThat(compose.mainClock.currentTime - start).isLessThan(100) // the animated drawing needs 300 ms to settle
        assertThat(plus() to tick()).isEqualTo(false to true)
    }
}
