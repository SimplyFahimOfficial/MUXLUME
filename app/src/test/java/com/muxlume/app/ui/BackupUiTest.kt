package com.muxlume.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxlume.app.ui.library.BackupScreen
import com.muxlume.app.ui.theme.MuxlumeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupUiTest {
    @get:Rule val compose = createComposeRule()

    private fun open() = compose.setContent { MuxlumeTheme { BackupScreen(Nav(rememberNavController())) } }

    @Test
    fun `a short passphrase is explained and cannot be saved`() {
        open()
        compose.onNodeWithText("Choose where to save").assertIsNotEnabled()
        compose.onAllNodesWithText("Passphrase")[0].performTextInput("short")
        compose.onNodeWithText("Use at least 8 characters.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose where to save").assertIsNotEnabled()
    }

    @Test
    fun `saving needs two matching passphrases`() {
        open()
        compose.onAllNodesWithText("Passphrase")[0].performTextInput("correct horse")
        compose.onAllNodesWithText("Repeat passphrase")[0].performTextInput("correct hose")
        compose.onNodeWithText("The two passphrases are different.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose where to save").assertIsNotEnabled()
    }

    @Test
    fun `matching passphrases enable saving`() {
        open()
        compose.onAllNodesWithText("Passphrase")[0].performTextInput("correct horse")
        compose.onAllNodesWithText("Repeat passphrase")[0].performTextInput("correct horse")
        compose.onNodeWithText("Choose where to save").performScrollTo().assertIsEnabled()
    }
}
