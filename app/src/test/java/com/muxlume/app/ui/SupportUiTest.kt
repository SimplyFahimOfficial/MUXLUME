package com.muxlume.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxlume.app.ui.library.SupportScreen
import com.muxlume.app.ui.theme.MuxlumeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SupportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `tapping the QR code enlarges it with payee and UPI id, Close dismisses it`() {
        compose.setContent { MuxlumeTheme { SupportScreen(onBack = {}) } }
        compose.onAllNodesWithText("Payee: Md Fahim").assertCountEquals(1)

        compose.onNodeWithContentDescription("UPI QR code for SimplyFahim@sbi").performClick()

        compose.onAllNodesWithText("Payee: Md Fahim").assertCountEquals(2) // card + dialog
        compose.onAllNodesWithText("SimplyFahim@sbi").assertCountEquals(2)
        compose.onNodeWithText("Close").assertIsDisplayed()

        compose.onNodeWithText("Close").performClick()
        compose.onAllNodesWithText("Payee: Md Fahim").assertCountEquals(1)
    }

    @Test
    fun `shows the project repository and no personal profile links`() {
        compose.setContent { MuxlumeTheme { SupportScreen(onBack = {}) } }
        compose.onNodeWithText("SimplyFahimOfficial/MUXLUME").assertExists()
        compose.onAllNodesWithText("Instagram").assertCountEquals(0)
        compose.onAllNodesWithText("@SimplyFahimofficial").assertCountEquals(0)
        compose.onNodeWithText("More ways to pay").assertExists()
    }
}
