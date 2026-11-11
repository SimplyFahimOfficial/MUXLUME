package com.muxlume.app.data.epg

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Guide times are stored as absolute instants and shown in the device's time zone. A "day" runs
 * from local midnight to the next local midnight, so on a daylight-saving change it is 23 or 25
 * hours long.
 */
object EpgTime {
    fun time(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

    fun range(start: Long, stop: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        "${time(start, zone, locale)} – ${time(stop, zone, locale)}"

    /** "Tuesday, 10 November" (localised). */
    fun date(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

    /** Epoch seconds of local midnight at the start of [date]. */
    fun startOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = date.atStartOfDay(zone).toEpochSecond()

    /** The day [offset] days from [today]: its start and end (the next midnight). */
    fun dayWindow(today: LocalDate, offset: Int, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val date = today.plusDays(offset.toLong())
        return startOfDay(date, zone) to startOfDay(date.plusDays(1), zone)
    }
}
