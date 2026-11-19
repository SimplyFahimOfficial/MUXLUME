package com.muxlume.app.recording

import com.muxlume.app.data.db.RecordingEntity
import com.muxlume.app.data.db.RecordingState

/** What adding a recording would run into, found before it is saved. */
class RecordingWarning(
    /** The account's connection limit when adding the recording would exceed it at some moment, else null. */
    val overLimit: Int?,
    val shortened: List<Shortened>,
) {
    /** A recording that ends [seconds] early because another one on the same channel starts. [isNew]: it is the one being added. */
    class Shortened(val title: String, val isNew: Boolean, val seconds: Long)
}

/**
 * Checks a new recording against the ones of its source that are waiting or running, using their padded windows.
 * Windows are half-open, so one that ends when another starts does not overlap it. A recording on the same channel
 * ends where the next one there starts (as RecordingService does), so those never count twice.
 */
object RecordingConflicts {
    /** [limit]: the connections the account allows at once; null or below 1 when unknown, which checks nothing but truncation. */
    fun check(limit: Int?, existing: List<RecordingEntity>, proposed: RecordingEntity): RecordingWarning? {
        val others = existing.filter { it.sourceId == proposed.sourceId && it.id != proposed.id && it.state in ACTIVE }
        val all = others + proposed

        val shortened = buildList {
            val end = endOf(proposed, all)
            if (end < proposed.paddedEnd) add(RecordingWarning.Shortened(proposed.title, isNew = true, seconds = proposed.paddedEnd - end))
            for (e in others) {
                val lost = endOf(e, others) - endOf(e, all)
                if (lost > 0) add(RecordingWarning.Shortened(e.title, isNew = false, seconds = lost))
            }
        }

        val from = proposed.paddedStart
        val to = endOf(proposed, all)
        val windows = others.map { it.paddedStart to endOf(it, all) }.filter { (s, e) -> s < to && e > from }
        val over = limit?.takeIf { it > 0 && peak(windows, from, to) >= it }
        return if (over == null && shortened.isEmpty()) null else RecordingWarning(over, shortened)
    }

    private val ACTIVE = setOf(RecordingState.SCHEDULED, RecordingState.RECORDING)

    /** When [r] stops: its padded end, or where the next waiting recording of its channel starts (a running one has already started). */
    private fun endOf(r: RecordingEntity, among: List<RecordingEntity>): Long {
        if (r.state == RecordingState.RECORDING) return r.paddedEnd
        val next = among.filter { it.stableKey == r.stableKey && it.state == RecordingState.SCHEDULED && it.paddedStart > r.paddedStart }.minOfOrNull { it.paddedStart }
        return minOf(r.paddedEnd, next ?: Long.MAX_VALUE)
    }

    /** The most windows that are open at one moment within [from, to). Ends sort before starts at the same moment. */
    private fun peak(windows: List<Pair<Long, Long>>, from: Long, to: Long): Int {
        val events = windows.flatMap { (s, e) -> listOf(maxOf(s, from) to 1, minOf(e, to) to -1) }.sortedWith(compareBy({ it.first }, { it.second }))
        var open = 0
        var best = 0
        for ((_, change) in events) {
            open += change
            best = maxOf(best, open)
        }
        return best
    }
}
