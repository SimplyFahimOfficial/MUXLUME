package com.muxlume.app.distribution

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdatesTest {
    @Test
    fun `compares dotted numbers, not text`() {
        assertThat(isNewerVersion("v1.2.10", "1.2.9")).isTrue()
        assertThat(isNewerVersion("1.0", "1.0.0")).isFalse()
        assertThat(isNewerVersion("0.1.0", "0.1.0-debug")).isFalse()
        assertThat(isNewerVersion("0.2.0", "0.10.0")).isFalse()
        assertThat(isNewerVersion("garbage", "0.1.0")).isFalse()
    }
}
