package com.muxlume.app.data.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class EpgTimeTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val hours = { w: Pair<Long, Long> -> (w.second - w.first) / 3600.0 }

    @Test
    fun `a day is 24 hours, but 23 and 25 on daylight saving changes`() {
        assertThat(hours(EpgTime.dayWindow(LocalDate.of(2026, 11, 10), 0, berlin))).isEqualTo(24.0)
        assertThat(hours(EpgTime.dayWindow(LocalDate.of(2026, 3, 29), 0, berlin))).isEqualTo(23.0)
        assertThat(hours(EpgTime.dayWindow(LocalDate.of(2026, 10, 25), 0, berlin))).isEqualTo(25.0)
        assertThat(hours(EpgTime.dayWindow(LocalDate.of(2026, 3, 28), 1, berlin))).isEqualTo(23.0) // offset from "today"
    }

    @Test
    fun `days chain without gaps or overlaps`() {
        val today = LocalDate.of(2026, 3, 27)
        for (offset in 0..5) {
            assertThat(EpgTime.dayWindow(today, offset, berlin).second).isEqualTo(EpgTime.dayWindow(today, offset + 1, berlin).first)
        }
    }

    @Test
    fun `times are shown in the given zone`() {
        // 12:00 UTC is 13:00 in Berlin (CET) and 07:00 in New York (EST).
        val noon = java.time.Instant.parse("2026-11-10T12:00:00Z").epochSecond
        assertThat(EpgTime.time(noon, berlin, Locale.UK)).isEqualTo("13:00")
        assertThat(EpgTime.time(noon, ZoneId.of("America/New_York"), Locale.UK)).isEqualTo("07:00")
        assertThat(EpgTime.range(noon, noon + 3600, berlin, Locale.UK)).isEqualTo("13:00 – 14:00")
    }

    @Test
    fun `local clock times skip the missing hour on the spring change`() {
        val dayStart = EpgTime.dayWindow(LocalDate.of(2026, 3, 29), 0, berlin).first
        val labels = (0..4).map { EpgTime.time(dayStart + it * 3600L, berlin, Locale.UK) }
        assertThat(labels).containsExactly("00:00", "01:00", "03:00", "04:00", "05:00").inOrder()
    }
}
