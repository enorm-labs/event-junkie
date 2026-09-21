package de.norm.events.scraper.clubost

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month

// Field mapping shared by the Club OST scrapers: the date and time renderings its Django
// templates emit, and the placeholder strings printed where a field is empty. Every case is
// asserted in ClubOstFieldMappingTest.

/**
 * Placeholder sentences the templates print for an absent value (an empty description, an empty
 * "more information" note, no flyer), mapped to `null`. Every event carries all three, which is
 * why Club OST imports no descriptions. Matched on the whole trimmed value, so a real description
 * containing a phrase is kept.
 */
private val PLACEHOLDER_VALUES =
    setOf(
        "no description available",
        "no further information",
        "no logo available",
        "more infos comming soon"
    )

/**
 * [text] trimmed, or `null` when blank or one of [PLACEHOLDER_VALUES].
 */
fun withoutPlaceholder(text: String?): String? =
    text
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.lowercase() !in PLACEHOLDER_VALUES }

/**
 * Month names as Django's `N` format renders them: AP-style, shortening only the seven long
 * names and leaving March through July spelled out. Keyed without the trailing period; `Sept.`
 * is AP's four-letter form, and `Sep.` is accepted too. The English rendering deliberately: the
 * site is bilingual on `Accept-Language` (German renders "7. August 2026 | 23:00 Uhr"), and the
 * shared scraper `WebClient` sends no such header ([ClubOstOverviewPageScraper]).
 */
private val DJANGO_MONTH_NAMES: Map<String, Month> =
    mapOf(
        "jan" to Month.JANUARY,
        "feb" to Month.FEBRUARY,
        "march" to Month.MARCH,
        "mar" to Month.MARCH,
        "april" to Month.APRIL,
        "apr" to Month.APRIL,
        "may" to Month.MAY,
        "june" to Month.JUNE,
        "jun" to Month.JUNE,
        "july" to Month.JULY,
        "jul" to Month.JULY,
        "aug" to Month.AUGUST,
        "sept" to Month.SEPTEMBER,
        "sep" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER,
        "nov" to Month.NOVEMBER,
        "dec" to Month.DECEMBER
    )

/** A Django `N j, Y` date — an AP-style month name, the day, and a four-digit year ("Aug. 7, 2026"). */
private val DJANGO_DATE_PATTERN = Regex("""^([A-Za-z]+)\.?\s+(\d{1,2}),\s*(\d{4})$""")

/**
 * A Django `P` clock time: hour, optional minutes, an `a.m.`/`p.m.` marker whose periods are
 * optional here ("11 p.m.", "11:55 p.m."). Django omits the minutes on the hour.
 */
private val DJANGO_TIME_PATTERN = Regex("""^(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s*m\.?$""", RegexOption.IGNORE_CASE)

/** Hours in a half-day — the offset that turns a 12-hour p.m. reading into a 24-hour one. */
private const val HALF_DAY_HOURS = 12

/**
 * Parses a date as Django's `N j, Y` renders it; the year is always stated. `null` for a
 * differently shaped or impossible input ("Feb. 30, 2026").
 */
fun parseClubOstDate(text: String?): LocalDate? {
    val match = DJANGO_DATE_PATTERN.find(text?.trim().orEmpty()) ?: return null
    val (monthName, day, year) = match.destructured
    return DJANGO_MONTH_NAMES[monthName.lowercase()]?.let { month ->
        try {
            LocalDate.of(year.toInt(), month, day.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }
}

/**
 * Parses a clock time as Django's `P` renders it: minutes dropped on the hour ("11 p.m."), and
 * the words midnight and noon for 12 a.m. and 12 p.m., matched before the numeric pattern; a
 * club whose nights start at midnight makes that a live case.
 */
fun parseClubOstTime(text: String?): LocalTime? {
    val normalized = text?.trim()?.lowercase().orEmpty()
    return when {
        normalized.isBlank() -> null
        normalized == MIDNIGHT_WORD -> LocalTime.MIDNIGHT
        normalized == NOON_WORD -> LocalTime.NOON
        else -> parseMeridiemTime(normalized)
    }
}

/** Django's word for what would otherwise print as "12 a.m.". */
private const val MIDNIGHT_WORD = "midnight"

/** Django's word for what would otherwise print as "12 p.m.". */
private const val NOON_WORD = "noon"

/** Parses the numeric `h[:mm] a.m./p.m.` shape of Django's `P` format into a 24-hour time. */
private fun parseMeridiemTime(text: String): LocalTime? {
    val match = DJANGO_TIME_PATTERN.find(text) ?: return null
    val (rawHour, rawMinute, meridiem) = match.destructured
    // Range-check the hour before converting: the 12-hour rule maps "25 p.m." onto 13:00, so an
    // out-of-range hour must be rejected here.
    return rawHour.toInt().takeIf { it in 1..HALF_DAY_HOURS }?.let { clockHour ->
        val hour = clockHour % HALF_DAY_HOURS + if (meridiem == "p") HALF_DAY_HOURS else 0
        try {
            LocalTime.of(hour, rawMinute.ifBlank { "0" }.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }
}
