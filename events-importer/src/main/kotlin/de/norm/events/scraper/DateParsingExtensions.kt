package de.norm.events.scraper

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// Shared date parsing for venue scrapers; the clock readers are in TimeParsingExtensions, and the
// dates a printed weekday decides are in WeekdayDateExtensions. Berlin
// venue websites write a date as ISO 8601 in schema.org JSON-LD ([parseIsoDate]), as German
// dotted `DD.MM.YYYY` / `DD.MM.YY` ([parseGermanDate], [parseGermanShortDate]), or as a
// `YYYYMMDD` permalink prefix ([parseBasicIsoDatePrefix]). Every function returns null for
// unparseable input rather than throwing.

/**
 * Sentinel for a [ScrapedEvent.eventDate] the page being parsed could not resolve. Two-page
 * importers use it on the step that lacks a date and rely on the other page during merge;
 * [AbstractTwoPageWebsiteImporter] drops any event still carrying it after the merge.
 */
val UNRESOLVED_EVENT_DATE: LocalDate = LocalDate.MIN

/**
 * The wall clock every venue here programmes in: to read an epoch or offset-stamped instant as
 * the venue's local time, and to give a [Clock] the zone whose "today" decides a year-less date.
 */
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** German dotted date format with a four-digit year (d.M.yyyy); accepts single- and double-digit day/month. */
private val GERMAN_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

/** German dotted date format with a two-digit year (d.M.yy); 2-digit year resolves to 2000–2099. */
private val GERMAN_SHORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yy")

/**
 * Parses the date portion from an ISO 8601 date-time, or a date-only string
 * ([String.substringBefore] returns the whole string when "T" is absent). The format schema.org
 * `MusicEvent` JSON-LD uses. Returns `null` for unparseable input.
 */
fun parseIsoDate(dateTimeStr: String): LocalDate? =
    try {
        LocalDate.parse(dateTimeStr.substringBefore("T"))
    } catch (_: DateTimeParseException) {
        null
    }

/**
 * Parses the date from a Kulturhäuser-platform `data-realdate` attribute, reading only the
 * leading ISO date of "2026-07-08 19:00:00 +0200"; preferred over a `DD.MM.YY` rendering because
 * it carries a four-digit year. Shared by Astra and Lido. `null` when absent or unparseable.
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
 * The date of a `YYYYMMDD-` permalink prefix (`20260816-nathan-evans` → `2026-08-16`), or `null`
 * without one. Shared by Tresor and Columbia Theater.
 */
fun parseBasicIsoDatePrefix(slug: String): LocalDate? {
    val digits = BASIC_ISO_DATE_PREFIX.find(slug)?.groupValues?.get(1) ?: return null
    return try {
        LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE)
    } catch (_: DateTimeParseException) {
        null
    }
}

private val BASIC_ISO_DATE_PREFIX = Regex("""^(\d{8})-""")

/**
 * Parses a German dotted date with a four-digit year (`DD.MM.YYYY`), the most common rendering on
 * Berlin venue pages; single-digit day/month accepted. `null` for blank or unparseable input.
 */
fun parseGermanDate(text: String?): LocalDate? = parseGerman(text, GERMAN_DATE_FORMATTER)

/**
 * Parses a German dotted date with a two-digit year (`DD.MM.YY`, Astra's "11.12.26"); years
 * resolve to 2000–2099, single-digit day/month accepted.
 */
fun parseGermanShortDate(text: String?): LocalDate? = parseGerman(text, GERMAN_SHORT_DATE_FORMATTER)

/**
 * Maps a German month abbreviation onto its [Month], case- and punctuation-insensitively. Spelled
 * out rather than parsed with a [Locale.GERMAN][java.util.Locale.GERMAN] formatter: the JDK's
 * CLDR abbreviations carry a trailing dot, and spell March `Mrz` where sites write `Mär` (or
 * `Maer`). Every March spelling is accepted including the full `März`, the one month German
 * does not abbreviate (Metropol writes `Aug.` but `März`). Shared by Soda, Velomax,
 * Admiralspalast and Metropol.
 */
fun parseGermanMonthAbbreviation(text: String?): Month? = GERMAN_MONTH_ABBREVIATIONS[text?.trim(',', '.', ' ')?.lowercase()]

/**
 * Maps a German two-letter weekday abbreviation onto its [DayOfWeek], for [inferYearForWeekday]
 * on a year-less date ("SA 08.08."). Spelled out for the same reason as
 * [parseGermanMonthAbbreviation]. Shared by Arcanoa, Club der Visionäre, Duncker, gART.n, Kater
 * and Wild at Heart; a page spelling the weekday out or in English is read by that venue's own
 * map.
 */
fun parseGermanWeekdayAbbreviation(text: String?): DayOfWeek? = GERMAN_WEEKDAY_ABBREVIATIONS[text?.lowercase()]

/**
 * Maps a full German weekday name onto its [DayOfWeek], for the venues that write "Donnerstag"
 * (Roadrunner, Soda).
 */
fun parseGermanWeekday(text: String?): DayOfWeek? = GERMAN_WEEKDAYS[text?.lowercase()]

/**
 * Maps an English three-letter weekday abbreviation onto its [DayOfWeek], for Junction Bar and
 * Monster Ronsons. Renate mixes both spellings and falls back to
 * [parseGermanWeekdayAbbreviation]; the key spaces are disjoint.
 */
fun parseEnglishWeekdayAbbreviation(text: String?): DayOfWeek? = ENGLISH_WEEKDAY_ABBREVIATIONS[text?.lowercase()]

/** Maps a full English weekday name ("Friday", as Morphine writes it) onto its [DayOfWeek]. */
fun parseEnglishWeekday(text: String?): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }

private val GERMAN_WEEKDAY_ABBREVIATIONS: Map<String, DayOfWeek> =
    mapOf(
        "mo" to DayOfWeek.MONDAY,
        "di" to DayOfWeek.TUESDAY,
        "mi" to DayOfWeek.WEDNESDAY,
        "do" to DayOfWeek.THURSDAY,
        "fr" to DayOfWeek.FRIDAY,
        "sa" to DayOfWeek.SATURDAY,
        "so" to DayOfWeek.SUNDAY
    )

private val GERMAN_WEEKDAYS: Map<String, DayOfWeek> =
    mapOf(
        "montag" to DayOfWeek.MONDAY,
        "dienstag" to DayOfWeek.TUESDAY,
        "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY,
        "freitag" to DayOfWeek.FRIDAY,
        "samstag" to DayOfWeek.SATURDAY,
        "sonntag" to DayOfWeek.SUNDAY
    )

private val ENGLISH_WEEKDAY_ABBREVIATIONS: Map<String, DayOfWeek> =
    mapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY
    )

/**
 * The twelve German month names in calendar order, `Januar` at index 0. A venue builds the form
 * it matches against (a set, a map, a regex alternation) from this list.
 */
val GERMAN_MONTH_NAMES: List<String> =
    listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

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
