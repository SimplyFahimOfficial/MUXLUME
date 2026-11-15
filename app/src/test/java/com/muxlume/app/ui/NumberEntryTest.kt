package com.muxlume.app.ui

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.muxlume.app.ui.player.NumberEntry
import org.junit.Test

class NumberEntryTest {
    @Test
    fun `digits build a number, leading zeros do not matter, and committing clears`() {
        val e = NumberEntry()
        assertThat(e.commit()).isNull()
        listOf(0, 0, 7).forEach(e::append)
        assertThat(e.digits).isEqualTo("007")
        assertThat(e.commit()).isEqualTo(7)
        assertThat(e.digits).isEmpty()
    }

    @Test
    fun `no more than five digits are taken, and clear throws the entry away`() {
        val e = NumberEntry()
        repeat(9) { e.append(9) }
        assertThat(e.commit()).isEqualTo(99_999)
        e.append(4)
        e.clear()
        assertThat(e.commit()).isNull()
    }

    @Test
    fun `number keys and numpad keys are digits, other keys are not`() {
        assertThat(NumberEntry.digitOf(KeyEvent.KEYCODE_0)).isEqualTo(0)
        assertThat(NumberEntry.digitOf(KeyEvent.KEYCODE_9)).isEqualTo(9)
        assertThat(NumberEntry.digitOf(KeyEvent.KEYCODE_NUMPAD_5)).isEqualTo(5)
        assertThat(NumberEntry.digitOf(KeyEvent.KEYCODE_ENTER)).isNull()
        assertThat(NumberEntry.digitOf(KeyEvent.KEYCODE_A)).isNull()
    }
}
