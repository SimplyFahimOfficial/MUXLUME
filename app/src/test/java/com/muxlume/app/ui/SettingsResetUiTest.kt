package com.muxlume.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.AppContainer
import com.muxlume.app.MuxlumeApp
import com.muxlume.app.data.AppSettings
import com.muxlume.app.data.db.SourceType
import com.muxlume.app.data.ThemeMode
import com.muxlume.app.data.db.SourceEntity
import com.muxlume.app.ui.library.SettingsScreen
import com.muxlume.app.ui.theme.MuxlumeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Settings → Delete all data from the screen: confirm, wait for the reset, toast, and the way home. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsResetUiTest {
    @get:Rule val compose = createComposeRule()

    /** The test uses the app's real settings file; a failed run must not leave it set up for the next test class. */
    @After fun cleanUp() {
        // Not dataReset.run(): its playback stop waits for the main thread, which this thread is.
        runCatching { runBlocking(Dispatchers.IO) { container.db.clearAllTables(); container.settings.resetAll() } }
    }

    private val container: AppContainer get() = (RuntimeEnvironment.getApplication() as MuxlumeApp).container

    @Test
    fun `confirming resets everything, says so and goes home, while Cancel resets nothing`() {
        val c = container
        runBlocking {
            c.db.sources().insert(SourceEntity(name = "S", type = SourceType.M3U_URL, secret = "x", label = "l", allowHttp = false, createdAt = 0))
            c.settings.setOnboardingDone()
            c.settings.setTheme(ThemeMode.LIGHT)
        }
        compose.setContent {
            MuxlumeTheme {
                val controller = rememberNavController()
                val nav = Nav(controller)
                NavHost(controller, startDestination = SettingsRoute) {
                    composable<SettingsRoute> { SettingsScreen(nav) }
                    composable<MainRoute> { Text("home reached") }
                }
            }
        }

        compose.onNodeWithText("Delete all data").performScrollTo().performClick()
        compose.onNodeWithText("Recording files in that folder", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertThat(runBlocking { c.db.sources().allNow() }).hasSize(1) // Cancel touched nothing
        assertThat(runBlocking { c.settings.current().theme }).isEqualTo(ThemeMode.LIGHT)

        compose.onNodeWithText("Delete all data").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("home reached")).fetchSemanticsNodes().isNotEmpty() }

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("All data deleted")
        assertThat(runBlocking { c.db.sources().allNow() }).isEmpty()
        assertThat(runBlocking { c.settings.current() }).isEqualTo(AppSettings())
        assertThat(c.dataReset.running.value).isFalse()
    }
}
