package com.muxlume.app.recording

import com.google.common.truth.Truth.assertThat
import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState
import org.junit.Test

class RecordingConflictsTest {
    private fun rec(
        key: String, start: Long, end: Long, source: Long = 1, state: RecordingState = RecordingState.SCHEDULED, lead: Long = 0, trail: Long = 0, id: Long = 0,
    ) = RecordingEntity(
        id = id, sourceId = source, stableKey = key, channelName = key, title = "T-$key-$start", startAt = start, endAt = end, state = state, leadSeconds = lead, trailSeconds = trail,
    )

    private fun check(limit: Int?, proposed: RecordingEntity, vararg existing: RecordingEntity) = RecordingConflicts.check(limit, existing.toList(), proposed)

    @Test
    fun `a second recording on a one-connection account overlapping another one warns`() {
        val w = check(1, rec("b", 150, 250), rec("a", 100, 200, id = 1))!!
        assertThat(w.overLimit).isEqualTo(1)
        assertThat(w.shortened).isEmpty()
    }

    @Test
    fun `windows that only touch do not overlap, but padding that crosses does`() {
        assertThat(check(1, rec("b", 200, 300), rec("a", 100, 200, id = 1))).isNull()
        assertThat(check(1, rec("b", 100, 200), rec("a", 200, 300, id = 1))).isNull()
        assertThat(check(1, rec("b", 200, 300, lead = 0), rec("a", 100, 200, trail = 30, id = 1))!!.overLimit).isEqualTo(1)
        assertThat(check(1, rec("b", 200, 300, lead = 30), rec("a", 100, 200, id = 1))!!.overLimit).isEqualTo(1)
    }

    @Test
    fun `two recordings that overlap the new one at different times do not warn on two connections`() {
        val first = rec("a", 0, 100, id = 1)
        val second = rec("c", 100, 200, id = 2)
        assertThat(check(2, rec("b", 50, 150), first, second)).isNull() // never more than one other at a time
        assertThat(check(1, rec("b", 50, 150), first, second)!!.overLimit).isEqualTo(1)
        // The same two overlapping each other inside the new one's window do use both connections.
        assertThat(check(2, rec("b", 50, 150), rec("a", 0, 120, id = 1), rec("c", 100, 200, id = 2))!!.overLimit).isEqualTo(2)
    }

    @Test
    fun `an unknown limit checks no connections`() {
        val clash = rec("a", 100, 200, id = 1)
        assertThat(check(null, rec("b", 150, 250), clash)).isNull()
        assertThat(check(0, rec("b", 150, 250), clash)).isNull()
        assertThat(check(-1, rec("b", 150, 250), clash)).isNull()
    }

    @Test
    fun `recordings of another source and finished ones do not count`() {
        assertThat(check(1, rec("b", 150, 250), rec("a", 100, 200, source = 2, id = 1))).isNull()
        assertThat(check(1, rec("b", 150, 250), rec("a", 100, 200, state = RecordingState.DONE, id = 1))).isNull()
        assertThat(check(1, rec("b", 150, 250), rec("a", 100, 200, state = RecordingState.RECORDING, id = 1))!!.overLimit).isEqualTo(1)
    }

    @Test
    fun `same-channel recordings end where the next one starts, so they never use two connections`() {
        // Existing waits on channel c until 200; the new one on c starts at 150, so the existing one will be cut at 150.
        val w = check(1, rec("c", 150, 250), rec("c", 100, 200, id = 1))!!
        assertThat(w.overLimit).isNull()
        val cut = w.shortened.single()
        assertThat(cut.isNew).isFalse()
        assertThat(cut.seconds).isEqualTo(50)
        assertThat(cut.title).isEqualTo("T-c-100")
    }

    @Test
    fun `a new recording that would be cut by a later one on its channel says so`() {
        val w = check(null, rec("c", 100, 250), rec("c", 200, 300, id = 1))!!
        val cut = w.shortened.single()
        assertThat(cut.isNew).isTrue()
        assertThat(cut.seconds).isEqualTo(50)
        assertThat(w.overLimit).isNull()
    }

    @Test
    fun `a running recording of the channel is not cut, it already started`() {
        val w = check(null, rec("c", 150, 250), rec("c", 100, 200, state = RecordingState.RECORDING, id = 1))
        assertThat(w).isNull()
    }

    @Test
    fun `a running recording that was cut short by the next one counts only until it stops`() {
        // Programme 100..200 with 50 s of trail would run to 250, but it was cut at 150 (where the next one on its channel starts): trail -50.
        val cut = rec("c", 100, 200, state = RecordingState.RECORDING, trail = -50, id = 1)
        val next = rec("c", 150, 300, id = 2)
        assertThat(check(2, rec("d", 100, 300), cut, next)).isNull() // one stream at a time on c, plus the new one
        val uncut = rec("c", 100, 200, state = RecordingState.RECORDING, trail = 50, id = 1)
        assertThat(check(2, rec("d", 100, 300), uncut, next)!!.overLimit).isEqualTo(2)
    }
}
