package com.muxlume.app.ui.guide

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.db.ProgramEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProgramActionsTest {
    @get:Rule val compose = createComposeRule()

    private val t0 = 1_800_000_000_000L
    private val program = ProgramEntity(sourceId = 1, channelId = "c", start = t0 / 1000 + 60, stop = t0 / 1000 + 120, title = "News")

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `a reminder is offered until the start and a recording until the end`() {
        compose.mainClock.autoAdvance = false
        var clock = t0
        var shown = ProgramActions(false, false)
        compose.setContent { shown = rememberProgramActions(program) { clock } }
        assertThat(shown).isEqualTo(ProgramActions(canRemind = true, canRecord = true))

        clock = t0 + 59_000
        compose.mainClock.advanceTimeBy(59_000)
        assertThat(shown).isEqualTo(ProgramActions(canRemind = true, canRecord = true))

        clock = t0 + 60_000
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(shown).isEqualTo(ProgramActions(canRemind = false, canRecord = true))

        clock = t0 + 120_000
        compose.mainClock.advanceTimeBy(60_100)
        assertThat(shown).isEqualTo(ProgramActions(canRemind = false, canRecord = false))
    }

    @Test
    fun `coming back after the timers did not run brings the actions up to date`() {
        compose.mainClock.autoAdvance = false
        var clock = t0
        var shown = ProgramActions(false, false)
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { shown = rememberProgramActions(program) { clock } } }
        assertThat(shown.canRemind).isTrue()

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED } // in the background, nothing ticks
        clock = t0 + 130_000 // the programme has ended meanwhile
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(shown).isEqualTo(ProgramActions(canRemind = true, canRecord = true)) // stale until resumed

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(shown).isEqualTo(ProgramActions(canRemind = false, canRecord = false))
    }

    @Test
    fun `a programme that already ended offers nothing and a running one only a recording`() {
        assertThat(programActions(program, program.stop)).isEqualTo(ProgramActions(false, false))
        assertThat(programActions(program, program.start)).isEqualTo(ProgramActions(false, true))
    }
}
