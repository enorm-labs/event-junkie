package de.norm.events.scraper

import java.time.DateTimeException

/**
 * [block]'s value, or null when it throws a [DateTimeException]: a text the formatter refuses, or a
 * day the calendar lacks (`LocalDate.of(2026, 2, 30)`). Any other exception still escapes, so a block
 * that also parses a number reads it with `toIntOrNull()` (#4650).
 */
inline fun <T> dateOrNull(block: () -> T): T? =
    try {
        block()
    } catch (_: DateTimeException) {
        null
    }
