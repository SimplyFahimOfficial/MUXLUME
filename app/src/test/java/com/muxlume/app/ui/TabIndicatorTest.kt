package com.muxlume.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun lightAt(xDp: Float): Boolean {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        return android.graphics.Color.red(bitmap.getPixel((xDp * 3).toInt(), (824 * 3))) > 200 // inside the pill, above the icon; the bar itself is dark
    }

    private fun selectLibrary(direction: LayoutDirection) {
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides direction) { MuxlumeTheme { MainScreen(Nav(rememberNavController())) } } }
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.waitForIdle()
    }

    private val first = 12f + 77.4f / 2 // centre of the first tab from the left edge, in dp
    private val last = 411f - first

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
