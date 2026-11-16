package com.muxlume.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FailoverPlanTest {
    private val candidates = listOf("2|a", "3|b", "4|c", "5|d", "6|e")

    @Test
    fun `the first untried candidate is next, in order`() {
        assertThat(FailoverPlan.next(candidates, emptySet(), 0)).isEqualTo("2|a")
        assertThat(FailoverPlan.next(candidates, setOf("2|a"), 1)).isEqualTo("3|b")
        assertThat(FailoverPlan.next(candidates, setOf("1|primary", "2|a", "4|c"), 1)).isEqualTo("3|b")
    }

    @Test
    fun `no server is tried twice, and nothing is left to try`() {
        assertThat(FailoverPlan.next(candidates, candidates.toSet(), 1)).isNull()
        assertThat(FailoverPlan.next(emptyList(), emptySet(), 0)).isNull()
    }

    @Test
    fun `three servers per failure at most`() {
        var tried = emptySet<String>()
        var attempts = 0
        val order = generateSequence {
            FailoverPlan.next(candidates, tried, attempts)?.also { tried += it; attempts++ }
        }.toList()
        assertThat(order).containsExactly("2|a", "3|b", "4|c").inOrder()
    }
}
