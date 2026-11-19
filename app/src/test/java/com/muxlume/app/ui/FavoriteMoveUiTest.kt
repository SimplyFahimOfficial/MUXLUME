package com.muxlume.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.db.ItemEntity
import com.muxlume.app.data.db.ItemKind
import com.muxlume.app.ui.live.ChannelOptionsSheet
import com.muxlume.app.ui.live.FavoriteMove
import com.muxlume.app.ui.theme.MuxlumeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FavoriteMoveUiTest {
    @get:Rule val compose = createComposeRule()
    private val item = ItemEntity(sourceId = 1, kind = ItemKind.LIVE, stableKey = "k", name = "News One", groupTitle = "G", logo = null, position = 0)

    private fun show(move: FavoriteMove?) = compose.setContent { MuxlumeTheme { ChannelOptionsSheet(item, move) {} } }

    @Test
    fun `Move up and Move down are actions with text, and only the possible ones are offered`() {
        val moves = mutableListOf<Boolean>()
        show(FavoriteMove(canUp = false, canDown = true) { moves += it })
        compose.waitForIdle()
        compose.onNodeWithText("Move down").assertIsDisplayed()
        compose.onAllNodesWithTextCount("Move up").let { assertThat(it).isEqualTo(0) } // first place: nothing above
        compose.onNode(hasText("Move down") and hasClickAction()).performClick()
        assertThat(moves).containsExactly(false)
    }

    @Test
    fun `a middle favorite offers both`() {
        show(FavoriteMove(canUp = true, canDown = true) {})
        compose.waitForIdle()
        compose.onNodeWithText("Move up").assertIsDisplayed()
        compose.onNodeWithText("Move down").assertIsDisplayed()
    }

    @Test
    fun `outside the favorites list there is no move action`() {
        show(null)
        compose.waitForIdle()
        compose.onNodeWithText("Pin to top").assertIsDisplayed()
        assertThat(compose.onAllNodesWithTextCount("Move up")).isEqualTo(0)
        assertThat(compose.onAllNodesWithTextCount("Move down")).isEqualTo(0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size
}
