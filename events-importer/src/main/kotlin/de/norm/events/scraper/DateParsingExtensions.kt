package de.norm.events.scraper

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs

// Shared date parsing utilities for venue scrapers. The clock readers are in
// TimeParsingExtensions.
//
// Berlin venue websites write a date three common ways:
// 1. ISO 8601 datetime — embedded in schema.org MusicEvent JSON-LD startDate
//    fields (e.g. "2026-05-16T20:00"). Read by [parseIsoDate].
// 2. European short date DD/MM/YY — used by some WordPress-based venue
//    sites (e.g. "21/09/26"). Parsed by [parseShortDate].
// 3. German dotted date DD.MM.YYYY / DD.MM.YY — rendered on many Berlin
//    venue pages (e.g. "10.07.2026", "29.06.26"). Parsed by [parseGermanDate]
//    (four-digit year) and [parseGermanShortDate] (two-digit year).
//
// All functions follow a null-safe convention: they return null for
// unparseable, blank, or missing input rather than throwing exceptions.

/**
 * Sentinel for a [ScrapedEvent.eventDate] that could not be resolved on the
 * page being parsed. Two-page importers use it on the overview/detail step that
 * lacks a date (e.g. Astra's dateless featured teaser, or a Madame Claude detail
 * page with no parseable date) and rely on the other page to supply the real
 * value during merge. [AbstractTwoPageWebsiteImporter] drops any event still
 * carrying this sentinel after the merge so it never reaches persistence.
 */
val UNRESOLVED_EVENT_DATE: LocalDate = LocalDate.MIN

/** European short date format (d/M/yy); 2-digit year resolves to 2000–2099. */
private val SHORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yy")

/** German dotted date format with a four-digit year (d.M.yyyy); accepts single- and double-digit day/month. */
private val GERMAN_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

/** German dotted date format with a two-digit year (d.M.yy); 2-digit year resolves to 2000–2099. */
private val GERMAN_SHORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yy")

/**
 * Parses the date portion from an ISO 8601 date-time string.
 *
 * Handles both full datetime (`"2026-05-16T20:00"`) and date-only
 * (`"2026-05-16"`) inputs — [String.substringBefore] returns the whole
 * string when "T" is absent.
 *
 * This is the standard date format used by schema.org `MusicEvent`
 * JSON-LD blocks (`startDate` field), which many venue websites embed
 * for SEO. Returns `null` for unparseable input.
 */
fun parseIsoDate(dateTimeStr: String): LocalDate? =
    try {
        LocalDate.parse(dateTimeStr.substringBefore("T"))
    } catch (_: DateTimeParseException) {
        null
    }

/**
 * Parses the date from a Kulturhäuser-platform `data-realdate` attribute
 * (e.g. "2026-07-08 19:00:00 +0200"), reading only the leading ISO date.
 *
 * Shared by venues on the Kulturhäuser platform (Astra, Lido). Preferred over
 * a human `DD.MM.YY` rendering because it carries a full four-digit year and no
 * two-digit-year pivot ambiguity. Returns `null` when the attribute is absent
 * (e.g. some detail headers) or unparseable, so the caller can fall back.
 */
fun parseRealDate(attr: String?): LocalDate? {
    if (attr.isNullOrBlank()) return null
    return try {
        LocalDate.parse(attr.trim().substringBefore(' '))
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Parses a European short date in `DD/MM/YY` format.
 *
 * Two-digit years are resolved to the 2000–2099 range (e.g. "26" → 2026).
 * Single-digit day/month values are also accepted (e.g. "1/9/26").
 *
 * This format is used by some WordPress-based Berlin venue websites
 * (e.g. Madame Claude) for event dates. Returns `null` for unparseable input.
 */
fun parseShortDate(text: String?): LocalDate? {
    if (text.isNullOrBlank()) return null
    return try {
        LocalDate.parse(text.trim(), SHORT_DATE_FORMATTER)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Parses a German dotted date with a four-digit year (`DD.MM.YYYY`).
 *
 * The most common human date rendering on Berlin venue pages (e.g. "10.07.2026",
 * "23.09.2026"). Single-digit day/month values are also accepted (e.g. "1.9.2026").
 * Returns `null` for null, blank, or unparseable input.
 */
fun parseGermanDate(text: String?): LocalDate? = parseGerman(text, GERMAN_DATE_FORMATTER)

/**
 * Parses a German dotted date with a two-digit year (`DD.MM.YY`).
 *
 * Used where venues render a short human year (e.g. Astra's "11.12.26", Clash's
 * "29.06.26"). Two-digit years resolve to the 2000–2099 range; single-digit
 * day/month values are also accepted. Returns `null` for null, blank, or
 * unparseable input.
 */
fun parseGermanShortDate(text: String?): LocalDate? = parseGerman(text, GERMAN_SHORT_DATE_FORMATTER)

/**
 * Maps a German month abbreviation onto its [Month], case- and punctuation-insensitively.
 *
 * Venues that render a calendar block write the month as a three-letter German abbreviation
 * ("Jul", "Okt", "Dez"). These are spelled out rather than parsed with a
 * [Locale.GERMAN][java.util.Locale.GERMAN] formatter for two reasons: the JDK's CLDR abbreviations
 * carry a trailing dot, and they spell March `Mrz` where some sites write `Mär` (or `Maer` where
 * the page is not UTF-8 clean). Every March spelling is accepted, including the full `März` —
 * German abbreviates each month to three letters *except* March, which venues therefore render
 * unabbreviated in an otherwise abbreviated column (Metropol writes `Aug.` but `März`).
 *
 * Shared by the venues whose listings render this calendar block — Soda, Velomax, Admiralspalast
 * and Metropol.
 */
fun parseGermanMonthAbbreviation(text: String?): Month? = GERMAN_MONTH_ABBREVIATIONS[text?.trim(',', '.', ' ')?.lowercase()]

private val GERMAN_MONTH_ABBREVIATIONS: Map<String, Month> =
    mapOf(
        "jan" to Month.JANUARY,
        "feb" to Month.FEBRUARY,
        "mär" to Month.MARCH,
        "märz" to Month.MARCH,
        "mrz" to Month.MARCH,
        "maer" to Month.MARCH,
        "maerz" to Month.MARCH,
        "apr" to Month.APRIL,
        "mai" to Month.MAY,
        "jun" to Month.JUNE,
        "jul" to Month.JULY,
        "aug" to Month.AUGUST,
        "sep" to Month.SEPTEMBER,
        "okt" to Month.OCTOBER,
        "nov" to Month.NOVEMBER,
        "dez" to Month.DECEMBER
    )

/** Shared null-safe parse for the two German dotted-date formatters. */
private fun parseGerman(
    text: String?,
    formatter: DateTimeFormatter
): LocalDate? {
    if (text.isNullOrBlank()) return null
    return try {
        LocalDate.parse(text.trim(), formatter)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Picks the calendar year for a year-less [monthDay], using a known [weekday] as
 * the disambiguator.
 *
 * Retro venue listings render dates without a year (e.g. "Fr 03.07." or
 * "Freitag, 29. Mai") and often leave recently-passed events on the page, so the
 * naive "assume this year, roll to next if already past" rule guesses wrong for a
 * stale event. Instead, among the candidate years in `today ± [yearWindow]`, only
 * those whose date lands on the stated [weekday] qualify, and the one **closest to
 * today** wins — so a just-passed event resolves to this year rather than a distant
 * future repeat. When [weekday] is `null` (unparseable), the nearest occurrence to
 * today across all candidate years is used. Shared by the retro single-page
 * scrapers (Roadrunner, Duncker).
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
