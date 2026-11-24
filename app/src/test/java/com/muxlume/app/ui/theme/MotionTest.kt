package com.muxlume.app.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MotionTest {
    private fun spring(spec: Any) = spec as SpringSpec<*>

    @Test
    fun `reduced motion snaps every kind and speed`() {
        val motion = Motion(reduced = true)
        Speed.entries.forEach {
            assertThat(motion.spatial<Float>(it)).isInstanceOf(SnapSpec::class.java)
            assertThat(motion.effects<Float>(it)).isInstanceOf(SnapSpec::class.java)
        }
    }

    @Test
    fun `the springs are those of the Material 3 Standard scheme`() {
        val motion = Motion(reduced = false)
        listOf(Speed.FAST to 1400f, Speed.DEFAULT to 700f, Speed.SLOW to 300f).forEach { (speed, stiffness) ->
            assertThat(spring(motion.spatial<Float>(speed)).stiffness).isEqualTo(stiffness)
            assertThat(spring(motion.spatial<Float>(speed)).dampingRatio).isEqualTo(0.9f) // a little give for things that move
        }
        listOf(Speed.FAST to 3800f, Speed.DEFAULT to 1600f, Speed.SLOW to 800f).forEach { (speed, stiffness) ->
            assertThat(spring(motion.effects<Float>(speed)).stiffness).isEqualTo(stiffness)
            assertThat(spring(motion.effects<Float>(speed)).dampingRatio).isEqualTo(1f) // fades never overshoot
        }
    }
}
