package de.norm.events.scraper

import java.time.LocalDate

// How a venue's note names the date a postponed show moved to, read by resolvePostponement in Relocation.kt.

/**
 * True when [note] names [eventDate] as a show's new date. Huxleys writes "auf den 01.10.2026",
 * Columbia Theater "auf den 25.03." with no year, Uber Eats "auf den 24. April 2027", Badehaus
 * "in den März 2027". Modus names only the old one, "(verschoben aus 2026)", so an earlier origin
 * with no new date in the note counts too.
 */
internal fun movedHere(
    note: String,
    eventDate: LocalDate
): Boolean {
    val namesNewDate =
        POSTPONED_TO_DATE.containsMatchIn(note) || POSTPONED_TO_DAY.containsMatchIn(note) ||
            POSTPONED_TO_WRITTEN_DATE.containsMatchIn(note) || POSTPONED_TO_MONTH.containsMatchIn(note)
    return POSTPONED_TO_DATE.findAll(note).any { targetDate(it) == eventDate } ||
        POSTPONED_TO_DAY.findAll(note).any { it.groupValues[1].toInt() == eventDate.dayOfMonth && it.groupValues[2].toInt() == eventDate.monthValue } ||
        POSTPONED_TO_WRITTEN_DATE.findAll(note).any { writtenDate(it) == eventDate } ||
        POSTPONED_TO_MONTH.findAll(note).any { monthOf(it.groupValues[1]) == eventDate.monthValue && it.groupValues[2].toInt() == eventDate.year } ||
        (!namesNewDate && POSTPONED_FROM_YEAR.findAll(note).any { it.groupValues[1].toInt() < eventDate.year })
}

/** A new date with no year: Columbia Theater's "Verschoben vom 06.08. auf den 25.03.". */
private val POSTPONED_TO_DAY = Regex("""\bauf\s+(?:de[nm]\s+)?(\d{1,2})\.(\d{1,2})\.(?![\d.])""", RegexOption.IGNORE_CASE)

/** The month names a note writes, both spellings of März. */
private val MONTH_WORD = (GERMAN_MONTH_NAMES + "Maerz").joinToString("|")

/** A new date with the month written out: "wird auf den 24. April 2027 verlegt". */
private val POSTPONED_TO_WRITTEN_DATE =
    Regex("""\bauf\s+(?:de[nm]\s+)?(\d{1,2})\.\s*($MONTH_WORD)\s+(\d{4})""", RegexOption.IGNORE_CASE)

/** A new month only: Badehaus's "Debüttour wird in den März 2027 verschoben". */
private val POSTPONED_TO_MONTH = Regex("""\b(?:in\s+den|auf\s+den|in|auf)\s+($MONTH_WORD)\s+(\d{4})""", RegexOption.IGNORE_CASE)

/** The year a show moved away from: Modus's "(verschoben aus 2026)". */
private val POSTPONED_FROM_YEAR = Regex("""\bverschoben\s+(?:aus|von)\s+(\d{4})\b""", RegexOption.IGNORE_CASE)

/** The month number of a written German month, "Maerz" spelt either way. */
private fun monthOf(name: String): Int = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(name.replace("ae", "ä", ignoreCase = true), ignoreCase = true) } + 1

/** The date a [POSTPONED_TO_WRITTEN_DATE] match names, or null for an impossible one. */
private fun writtenDate(match: MatchResult): LocalDate? {
    val (day, month, year) = match.destructured
    return runCatching { LocalDate.of(year.toInt(), monthOf(month), day.toInt()) }.getOrNull()
}
