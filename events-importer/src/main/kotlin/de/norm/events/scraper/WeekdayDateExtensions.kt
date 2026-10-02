package de.norm.events.scraper

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.abs

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
 * apart. The caller decides whether the result is plausible, for example by its distance to
 * the dates around it, and logs the correction.
 */
fun neighbouringMonthOnWeekday(
    date: LocalDate,
    weekday: DayOfWeek
): LocalDate? =
    listOf(-1L, 1L)
        .mapNotNull { offset -> runCatching { date.plusMonths(offset).withDayOfMonth(date.dayOfMonth) }.getOrNull() }
        .singleOrNull { it.dayOfWeek == weekday }
