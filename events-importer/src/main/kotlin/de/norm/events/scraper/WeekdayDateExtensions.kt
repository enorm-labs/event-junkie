package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

// Dates that the weekday printed beside them decides. The weekday readers are in DateParsingExtensions.

/**
 * Picks the calendar year for a year-less [monthDay], using [weekday] as the disambiguator.
 * Retro listings ("Fr 03.07.") leave recently-passed events on the page, so "assume this year,
 * roll to next if past" guesses wrong for a stale event. Among the years in `today ±
 * [yearWindow]`, only those whose date lands on [weekday] qualify, and the closest to today
 * wins; with [weekday] `null`, the nearest occurrence. Shared by Roadrunner and Duncker.
 */
fun inferYearForWeekday(
    monthDay: MonthDay,
    weekday: DayOfWeek?,
    clock: Clock,
    yearWindow: Int = 2
): LocalDate {
    val today = LocalDate.now(clock)
    val candidates =
        ((today.year - yearWindow)..(today.year + yearWindow)).mapNotNull { year ->
            // MonthDay.atYear normalises 29 Feb to 28 Feb in common years, which is acceptable here.
            runCatching { monthDay.atYear(year) }.getOrNull()
        }
    val eligible = if (weekday != null) candidates.filter { it.dayOfWeek == weekday } else candidates
    val pool = eligible.ifEmpty { candidates }
    return pool.minByOrNull { abs(it.toEpochDay() - today.toEpochDay()) } ?: monthDay.atYear(today.year)
}

/**
 * The same day of the month as [date], one month earlier or later, that falls on the published
 * [weekday]. A page that prints the weekday beside a full date can still mistype the month: Golden
 * Gate printed "Sa. 03. September 2026" for Saturday 3 October (#2349). Returns `null` when no
 * neighbouring month fits. At most one can fit, because the two neighbours lie 59 to 62 days
 * apart. [dateCheckedAgainstWeekday] is the caller every scraper uses.
 */
fun neighbouringMonthOnWeekday(
    date: LocalDate,
    weekday: DayOfWeek
): LocalDate? =
    listOf(-1L, 1L)
        .mapNotNull { offset -> runCatching { date.plusMonths(offset).withDayOfMonth(date.dayOfMonth) }.getOrNull() }
        .singleOrNull { it.dayOfWeek == weekday }

/**
 * The date a heading means, read from its printed [date] and the [weekday] printed beside it.
 * Every scraper whose venue prints both goes through here, so they all decide the same way.
 *
 * - The weekday agrees, or none was printed: [date].
 * - The same day in a neighbouring month falls on the weekday ([neighbouringMonthOnWeekday])
 * and [plausible] accepts it: that date. The month was mistyped, as at Golden Gate (#2349).
 * - Otherwise: [date]. The weekday was mistyped, and the printed date is the better reading.
 * Dropping the night would lose a real event on a typo (#2368).
 *
 * Both disagreements log a `WARN` naming the [heading], the parsed date and the date kept, so a
 * venue that mistypes often shows in the log. [plausible] lets a caller that knows the dates
 * around the heading refuse a correction far from them; it does not see the printed date.
 */
fun dateCheckedAgainstWeekday(
    date: LocalDate,
    weekday: DayOfWeek?,
    heading: String,
    plausible: (LocalDate) -> Boolean = { true }
): LocalDate {
    if (weekday == null || date.dayOfWeek == weekday) return date
    val corrected = neighbouringMonthOnWeekday(date, weekday)?.takeIf(plausible)
    val kept = corrected ?: date
    logger.at(Level.WARN) {
        message =
            if (corrected != null) {
                "Heading '$heading' names a $weekday but $date is a ${date.dayOfWeek}, reading it as $corrected"
            } else {
                "Heading '$heading' names a $weekday but $date is a ${date.dayOfWeek}, and no neighbouring month fits, keeping $date"
            }
        payload =
            mapOf(
                LogFields.DATE_HEADING to heading,
                LogFields.PARSED_DATE to date.toString(),
                LogFields.CORRECTED_DATE to kept.toString()
            )
    }
    return kept
}
