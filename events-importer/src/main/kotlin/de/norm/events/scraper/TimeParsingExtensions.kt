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

/**
 * Parses the leading `HH:mm` of a longer clock string, or `null` for a missing or unparseable one.
 *
 * A REST API states a time as `HH:mm:ss` and carries no seconds worth keeping, so the prefix is
 * the whole value. Used by the WordPress-backed venues (Festsaal, Madame Claude).
 */
fun parseClockPrefix(raw: String?): LocalTime? = parseTime(raw?.trim()?.take(HH_MM_LENGTH)?.takeIf { it.isNotBlank() })

/**
 * Extracts the `HH:mm` time that [label] introduces in [text], or `null` when the label is absent.
 *
 * Venues flatten doors and start onto one line, so the label is the only thing separating them
 * (`"Einlass: 19:00 Beginn: 20:00"`). The colon is optional and the match ignores case.
 */
fun labelledTime(
    text: String,
    label: String
): String? = Regex("""$label\s*:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

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
