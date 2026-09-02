package de.norm.events.scraper.clubost

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month

// Field mapping shared by the Club OST overview and detail scrapers: the date and time
// renderings its Django templates emit, and the placeholder strings the templates print
// where a field is empty.
//
// Both pages state the same date and start time, so both scrapers parse them the same way —
// the overview so its fallback data is usable when a detail page fetch fails, the detail
// page because it is the primary source for the stored event. Every case is asserted in
// ClubOstFieldMappingTest, which is where the examples live.

/**
 * Placeholder sentences the templates print in place of an absent value — an empty
 * description, an empty "more information" note, an event with no flyer uploaded. They are
 * prose, not data, so a scraper maps them to `null` rather than storing them.
 *
 * Every event on the site currently carries all three, which is why Club OST imports no
 * descriptions: the venue publishes its programme through Resident Advisor and leaves these
 * CMS fields empty. Matched case-insensitively on the whole trimmed value, so a real
 * description that merely *contains* one of these phrases is kept.
 */
private val PLACEHOLDER_VALUES =
    setOf(
        "no description available",
        "no further information",
        "no logo available",
        "more infos comming soon"
    )

/**
 * Returns [text] trimmed, or `null` when it is blank or one of the templates'
 * [PLACEHOLDER_VALUES].
 */
fun withoutPlaceholder(text: String?): String? =
    text
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.lowercase() !in PLACEHOLDER_VALUES }

/**
 * Month names as Django's `N` date format renders them — AP-style abbreviations, which
 * shorten only the seven long month names and leave March through July spelled out.
 *
 * Keyed without the trailing period so the lookup is punctuation-insensitive. `Sept.` is the
 * four-letter form AP uses (not `Sep.`), and both spellings are accepted here because the
 * distinction is a styling choice a Django upgrade could revisit. This is the *English*
 * rendering deliberately: the site is bilingual on `Accept-Language` (German renders
 * "7. August 2026 | 23:00 Uhr"), and the shared scraper `WebClient` sends no such header, so
 * Django falls through to its default locale — see the [ClubOstOverviewPageScraper] KDoc.
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
 * A Django `P` clock time — an hour, optional minutes, and an `a.m.`/`p.m.` marker whose
 * periods the format always writes but which are accepted as optional here ("11 p.m.",
 * "11:55 p.m."). Django omits the minutes entirely on the hour, which is why they are
 * optional rather than padded.
 */
private val DJANGO_TIME_PATTERN = Regex("""^(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s*m\.?$""", RegexOption.IGNORE_CASE)

/** Hours in a half-day — the offset that turns a 12-hour p.m. reading into a 24-hour one. */
private const val HALF_DAY_HOURS = 12

/**
 * Parses a date as Django's `N j, Y` format renders it.
 *
 * The year is stated in full on every listing, so no weekday-based year inference is needed
 * here — unlike the retro venue pages that omit it. Returns `null` for null, blank,
 * differently-shaped, or calendrically impossible input ("Feb. 30, 2026").
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
 * Parses a clock time as Django's `P` format renders it.
 *
 * `P` drops the minutes on the hour ("11 p.m.") and prints them otherwise ("11:55 p.m."),
 * and it substitutes the words **midnight** and **noon** for what would be 12 a.m. and
 * 12 p.m. — so those two are matched before the numeric pattern rather than left to fall
 * through as unparseable. A club whose nights start at midnight makes that a live case, not
 * a hypothetical one. Returns `null` for null, blank, or unparseable input.
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
    // Range-check the hour before converting: the 12-hour rule maps any reading onto a valid
    // 24-hour one ("25 p.m." would become 13:00), so an out-of-range hour has to be rejected
    // here rather than left for LocalTime.of to catch. 12 a.m./p.m. never reach here from
    // Django (it writes the two words above), but the same rule handles them correctly.
    return rawHour.toInt().takeIf { it in 1..HALF_DAY_HOURS }?.let { clockHour ->
        val hour = clockHour % HALF_DAY_HOURS + if (meridiem == "p") HALF_DAY_HOURS else 0
        try {
            LocalTime.of(hour, rawMinute.ifBlank { "0" }.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }
}
