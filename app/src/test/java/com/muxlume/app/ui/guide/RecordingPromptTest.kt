package com.muxlume.app.ui.guide

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.recording.RecordingWarning
import com.muxlume.app.ui.theme.MuxlumeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecordingPromptTest {
    @get:Rule val compose = createComposeRule()
    private val warning = RecordingWarning(1, listOf(RecordingWarning.Shortened("News", isNew = false, seconds = 90)))

    private fun ask(prompt: RecordingPrompt, onAnswer: (Boolean) -> Unit): Job = CoroutineScope(Dispatchers.Main).launch { onAnswer(prompt.confirm(warning)) }

    @Test
    fun `the warning names the limit and the shortened recording, and Record anyway answers yes`() {
        val prompt = RecordingPrompt()
        var answer: Boolean? = null
        compose.setContent { MuxlumeTheme { prompt.Dialog() } }
        ask(prompt) { answer = it }
        compose.waitForIdle()
        compose.onNodeWithText("This playlist allows 1 connection at a time", substring = true).assertIsDisplayed()
        compose.onNodeWithText("would end 2 min early", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Record anyway").performClick()
        compose.waitForIdle()
        assertThat(answer).isTrue()
    }

    @Test
    fun `Cancel answers no, and leaving the screen answers nothing`() {
        val prompt = RecordingPrompt()
        var answer: Boolean? = null
        compose.setContent { MuxlumeTheme { prompt.Dialog() } }
        ask(prompt) { answer = it }
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertThat(answer).isFalse()

        var left: Boolean? = null
        val job = ask(prompt) { left = it }
        compose.waitForIdle()
        job.cancel() // the sheet or player was closed: the caller never schedules anything
        compose.waitForIdle()
        assertThat(left).isNull()
    }
}
