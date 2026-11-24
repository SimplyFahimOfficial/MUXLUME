package com.muxlume.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.ui.main.MainScreen
import com.muxlume.app.ui.theme.MuxlumeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bottom bar's indicator ends up under the selected tab, also where the tabs run right to left. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TabIndicatorTest {
    @get:Rule val compose = createComposeRule()

    /** Whether the bar is light at [fraction] of its width, a little below its top edge (inside the indicator, above the icons). */
    private fun lightAt(fraction: Float): Boolean {
        val bounds = compose.onNodeWithTag("bottom-bar").fetchSemanticsNode().boundsInRoot
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixel = bitmap.getPixel((bounds.left + fraction * bounds.width).toInt(), (bounds.top + 0.15f * bounds.height).toInt())
        return android.graphics.Color.red(pixel) > 200 // the bar itself is dark
    }

    private fun selectLibrary(direction: LayoutDirection) {
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides direction) { MuxlumeTheme { MainScreen(Nav(rememberNavController())) } } }
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.waitForIdle()
    }

    private val first = 0.1f // the middle of the first of five tabs, from the left edge of the bar
    private val last = 0.9f

    @Test
    fun `left to right the indicator moves to the last tab on the right`() {
        selectLibrary(LayoutDirection.Ltr)
        assertThat(lightAt(last) to lightAt(first)).isEqualTo(true to false)
    }

    @Test
    fun `right to left the last tab is on the left and the indicator follows it`() {
        selectLibrary(LayoutDirection.Rtl)
        assertThat(lightAt(first) to lightAt(last)).isEqualTo(true to false)
    }
}
