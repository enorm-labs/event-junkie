package de.norm.events.scraper.kallasch

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * The dates of an RFC 5545 `RRULE` from [start] up to [limit], [start] included: `DAILY`, `WEEKLY` on its `BYDAY` days,
 * `MONTHLY` on an ordinal `BYDAY` (`1TH`, `-1TH`) or a `BYMONTHDAY`, and `YEARLY` on the start's day, each with `INTERVAL`,
 * `UNTIL` and `COUNT`. `null` for a rule outside that subset, so the caller keeps the start date alone rather than guessing.
 */
internal fun recurrenceDates(
    rule: String,
    start: LocalDate,
    limit: LocalDate
): List<LocalDate>? {
    val parts =
        rule
            .split(';')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.uppercase() to v.uppercase() } }
            .toMap()
    if (parts.keys.any { it !in SUPPORTED_PARTS }) return null
    val interval = parts["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
    val until = parts["UNTIL"]?.let { LocalDate.parse(it.take(BASIC_DATE_LENGTH), BASIC_DATE) } ?: LocalDate.MAX
    val slots =
        when (parts["FREQ"]) {
            "DAILY" -> generateSequence(start) { it.plusDays(interval) }
            "WEEKLY" -> weeklySlots(parts["BYDAY"], start, interval)
            "MONTHLY" -> monthlySlots(parts["BYDAY"], parts["BYMONTHDAY"], start, interval)
            "YEARLY" -> generateSequence(start) { it.plusYears(interval) }
            else -> null
        } ?: return null
    val dates = slots.dropWhile { it < start }.takeWhile { it <= minOf(limit, until) }
    return parts["COUNT"]?.toIntOrNull()?.let { dates.take(it).toList() } ?: dates.toList()
}

private fun weeklySlots(
    byDay: String?,
    start: LocalDate,
    interval: Long
): Sequence<LocalDate>? {
    val days = byDay?.split(',')?.map { WEEKDAYS[it] ?: return null }?.sorted() ?: listOf(start.dayOfWeek)
    return generateSequence(start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(interval) }
        .flatMap { monday -> days.asSequence().map { monday.plusDays(it.value - 1L) } }
}

private fun monthlySlots(
    byDay: String?,
    byMonthDay: String?,
    start: LocalDate,
    interval: Long
): Sequence<LocalDate>? {
    val months = generateSequence(start.withDayOfMonth(1)) { it.plusMonths(interval) }
    if (byDay == null) {
        val day = byMonthDay?.toIntOrNull() ?: start.dayOfMonth
        return months.filter { day <= it.lengthOfMonth() }.map { it.withDayOfMonth(day) }
    }
    val match = ORDINAL_WEEKDAY.matchEntire(byDay) ?: return null
    val ordinal = match.groupValues[1].toIntOrNull() ?: return null
    val weekday = WEEKDAYS[match.groupValues[2]] ?: return null
    // A fifth weekday the month lacks lands in a neighbouring month; such a month has no slot.
    return months.mapNotNull { first -> first.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday)).takeIf { it.month == first.month } }
}

private val SUPPORTED_PARTS = setOf("FREQ", "INTERVAL", "UNTIL", "COUNT", "BYDAY", "BYMONTHDAY", "WKST")

private val ORDINAL_WEEKDAY = Regex("""([+-]?[1-5])([A-Z]{2})""")

private val WEEKDAYS =
    mapOf(
        "MO" to DayOfWeek.MONDAY,
        "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY,
        "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY
    )

private const val BASIC_DATE_LENGTH = 8
private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
