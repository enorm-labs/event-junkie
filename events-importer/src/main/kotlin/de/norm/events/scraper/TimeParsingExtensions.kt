package de.norm.events.scraper

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// Shared clock parsing utilities for venue scrapers. A venue renders a time standalone
// ("Einlass: 19:00"), behind a label on a line it shares with another time, or as the tail of an
// ISO 8601 stamp. Every reader returns null for blank or unparseable input rather than throwing.
// The date readers are in DateParsingExtensions.

/** Standard 24-hour time format (HH:mm) used by most Berlin venue websites. */
private val HH_MM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Attempts to parse [text] as a [LocalTime] using the given [formatter].
 *
 * Returns `null` if [text] is null, blank, or cannot be parsed — rather
 * than throwing an exception. This is the expected behavior for scrapers
 * where missing or malformed time values should degrade gracefully.
 */
fun parseTime(
    text: String?,
    formatter: DateTimeFormatter = HH_MM_FORMATTER
): LocalTime? {
    if (text.isNullOrBlank()) return null
    return try {
        LocalTime.parse(text.trim(), formatter)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** A clock with a one- or two-digit hour, as the 12-hour form prints it (`9:30`, `11:30`). */
private val H_MM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

private const val HOURS_PER_HALF_DAY = 12L

/**
 * Parses an `H:mm` [clock] and applies an English [meridiem] (`a`/`am`, `p`/`pm`), or returns
 * `null` for a missing or unparseable clock. Without a meridiem the clock is read as 24-hour.
 *
 * A venue that prints `Doors 11:30pm` beside a German `23:30 UHR` loses the evening if the
 * `pm` is dropped: the night party shows a midday doors time (#1949).
 */
fun parseClock(
    clock: String?,
    meridiem: String? = null
): LocalTime? {
    val time = parseTime(clock, H_MM_FORMATTER) ?: return null
    return when (meridiem?.trim()?.firstOrNull()?.lowercaseChar()) {
        'p' -> if (time.hour < HOURS_PER_HALF_DAY) time.plusHours(HOURS_PER_HALF_DAY) else time
        'a' -> if (time.hour.toLong() == HOURS_PER_HALF_DAY) time.minusHours(HOURS_PER_HALF_DAY) else time
        else -> time
    }
}

/**
 * Parses the leading `HH:mm` of a longer clock string, or `null` for a missing or unparseable one.
 *
 * A REST API states a time as `HH:mm:ss` and carries no seconds worth keeping, so the prefix is
 * the whole value. Used by the WordPress-backed venues (Festsaal, Madame Claude).
 */
fun parseClockPrefix(raw: String?): LocalTime? = parseTime(raw?.trim()?.take(HH_MM_LENGTH)?.takeIf { it.isNotBlank() })

/**
 * The words a venue puts before its doors time: "Einlass", "Doors", "Door", "Doors open", and
 * Berghain's "Tür". For [labelledClock].
 */
const val DOORS_LABELS = """einlass|doors?(?:\s+open)?|tür"""

/**
 * The words a venue puts before its start time: "Beginn" and a compound ending in it
 * ("Konzertbeginn"), "Start", "Starts", "Show", "Showtime". For [labelledClock].
 */
const val START_LABELS = """\p{L}*beginn|start|show"""

/**
 * The clock after a label, in every spelling the venues print: `19:30`, `19.30`, `20,00` (Insel's
 * typo), `19 Uhr`,
 * `19h`, `7pm`, `7.30 p.m.`, optionally led by a colon and up to two of "ab", "ca.", "um" ("Einlass ab ca. 18:45"). A bare hour
 * needs "Uhr", "h" or a meridiem, so "Beginn 1. Oktober" is not a clock, and a date such as
 * "19.10.2026" is not one either.
 */
private const val CLOCK_AFTER_LABEL =
    """\p{L}*\s*:?\s*(?:(?:ab|ca\.?|um|from|at)\s+){0,2}(\d{1,2})""" +
        """(?:(?:[:.,](\d{2}))(?!\.?\d)\s*(?:uhr|h)?(?!\p{L})|\s*(?:uhr|h)(?!\p{L})|(?=\s*[ap]\.?\s?m))""" +
        """(?:\s*([ap])\.?\s?m\.?(?!\p{L}))?"""

/**
 * The clock that one of [labels] (a regex alternation such as [DOORS_LABELS]) introduces in
 * [text], or `null` when none does. Venues flatten doors and start onto one line (`"Einlass:
 * 19:00 Beginn: 20:00"`), so the label is the only thing separating them. Case is ignored, the
 * label may carry a suffix ("Beginnt", "Startzeit"), and the clock is read leniently
 * ([CLOCK_AFTER_LABEL]); a 12-hour clock takes its meridiem ([parseClock]).
 */
fun labelledClock(
    text: String?,
    labels: String
): LocalTime? =
    text?.let { labelledClockPattern(labels).find(it) }?.destructured?.let { (hour, minute, meridiem) ->
        parseClock("$hour:${minute.ifEmpty { "00" }}", meridiem.ifEmpty { null })
    }

/**
 * The pattern [labelledClock] matches, for a scraper that drops the time line from a description
 * or finds the line that carries it.
 */
fun labelledClockPattern(labels: String): Regex = Regex("""(?<!\p{L})(?:$labels)$CLOCK_AFTER_LABEL""", RegexOption.IGNORE_CASE)

/**
 * Parses the time portion from an ISO 8601 date-time string.
 *
 * Extracts the part after "T" and delegates to [parseTime] for the
 * actual `HH:mm` parsing. Returns `null` if the string has no time
 * component or the time part is unparseable.
 *
 * This complements [parseIsoDate] for splitting schema.org `startDate`
 * values into separate date and time components.
 */
fun parseIsoTime(dateTimeStr: String): LocalTime? {
    val timePart = dateTimeStr.substringAfter("T", "")
    return parseTime(timePart)
}
