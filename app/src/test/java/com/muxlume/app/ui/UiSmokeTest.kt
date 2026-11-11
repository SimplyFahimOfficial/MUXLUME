package com.muxlume.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.ui.onboarding.AddSourceScreen
import com.muxlume.app.ui.onboarding.WelcomeScreen
import com.muxlume.app.ui.theme.MuxlumeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `fresh install starts on the welcome screen with the player-only disclaimer`() {
        compose.setContent { MuxlumeTheme { MuxlumeRoot() } }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Add a playlist")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("doesn't provide channels", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Explore first").assertIsDisplayed()
    }

    @Test
    fun `welcome actions`() {
        var added = false
        compose.setContent { MuxlumeTheme { WelcomeScreen(onAdd = { added = true }, onExplore = {}) } }
        compose.onNodeWithText("Add a playlist").performClick()
        assertThat(added).isTrue()
    }

    @Test
    fun `http link needs an explicit acknowledgement before import`() {
        compose.setContent { MuxlumeTheme { AddSourceScreen(null, onDone = {}, onBack = {}) } }
        compose.onNodeWithText("Add and import").assertIsNotEnabled()
        compose.onNodeWithText("Playlist link").performTextInput("http://iptv.example/list.m3u")
        compose.onNodeWithText("This server doesn't use encryption").assertIsDisplayed()
        compose.onNodeWithText("Add and import").assertIsNotEnabled()
        compose.onNodeWithText("I understand, allow unencrypted HTTP").performClick()
        compose.onNodeWithText("Add and import").assertIsEnabled()
    }

    @Test
    fun `xtream form validates before testing`() {
        compose.setContent { MuxlumeTheme { AddSourceScreen(null, onDone = {}, onBack = {}) } }
        compose.onNodeWithText("Xtream Codes").performClick()
        compose.onNodeWithText("Test connection").assertIsNotEnabled()
        compose.onNodeWithText("Server address").performTextInput("https://provider.example:8443")
        compose.onNodeWithText("Username").performTextInput("me")
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.onNodeWithText("Test connection").assertIsEnabled()
        compose.onNodeWithText("••••••").assertIsDisplayed() // shown masked
    }
}
