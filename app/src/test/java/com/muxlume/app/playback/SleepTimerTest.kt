package com.muxlume.app.playback

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.BufferMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private val minute = TimeUnit.MINUTES.toMillis(1)

    private fun TestScope.timer(onExpire: suspend () -> Unit) = SleepTimer(this, { currentTime }, onExpire)

    @Test
    fun `pauses once after the chosen minutes`() = runTest {
        var paused = 0
        val t = timer { paused++ }
        t.start(15)
        assertThat(t.state.value!!.minutes).isEqualTo(15)

        advanceTimeBy(15 * minute - 1)
        assertThat(paused).isEqualTo(0)
        advanceTimeBy(2)
        assertThat(paused).isEqualTo(1)
        assertThat(t.state.value).isNull()
    }

    @Test
    fun `starting again restarts and zero or cancel stops it`() = runTest {
        var paused = 0
        val t = timer { paused++ }
        t.start(15)
        advanceTimeBy(10 * minute)
        t.start(30) // replaces the first one
        advanceTimeBy(20 * minute)
        assertThat(paused).isEqualTo(0)
        advanceTimeBy(10 * minute + 1)
        assertThat(paused).isEqualTo(1)

        t.start(15)
        t.start(0)
        advanceTimeBy(60 * minute)
        assertThat(paused).isEqualTo(1)
        assertThat(t.state.value).isNull()
    }

    @Test
    fun `buffer modes keep the player's required ordering`() {
        BufferMode.entries.forEach {
            assertThat(it.minMs).isAtMost(it.maxMs)
            assertThat(it.startMs).isAtMost(it.minMs)
            assertThat(it.resumeMs).isAtMost(it.minMs)
        }
    }
}
