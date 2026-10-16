package de.norm.events.scraper.kallasch

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.CalendarProperty
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.calendarEvents
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.flatMapSkippingFailures
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.htmlParagraphText
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the Modern Events Calendar iCalendar feed of the Kallasch&. Times carry
 * `TZID=Europe/Berlin` and match the listing; the JSON-LD on the listing is an hour or two off and is
 * not read. A series is one entry whose `DTSTART` is its next date, with an `RRULE` (the monthly
 * quiz, slam and open mic) or an `RDATE` list (the jazz series). Each becomes one event per date
 * within [HORIZON_WEEKS], minus its `EXDATE`s, keyed by slug and date. Every occurrence takes the
 * series' own clock time: the jazz series' December `RDATE`s read `08:30`, a 12-hour slip for the
 * 20:30 every other date and the description name. The title's `KONZERT // ` style prefix repeats
 * the category and is dropped where the category maps to a type; a recurring concert names its
 * series, not an act, so only a one-off concert mints artists.
 */
class KallaschCalendarScraper(
    private val clock: Clock
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(calendar: String): List<ScrapedEvent> {
        val entries = calendarEvents(calendar)
        logger.info { "Found ${entries.size} Kallasch& calendar entr(y/ies)" }
        return entries.flatMapSkippingFailures(logger, "Kallasch& calendar entry") { parseEntry(it) }
    }

    private fun parseEntry(entry: Map<String, CalendarProperty>): List<ScrapedEvent> {
        val summary = entry["SUMMARY"]?.text?.let(::decodeHtmlEntities).blankToNull()
        val url = entry["URL"]?.value.blankToNull()
        val start = entry["DTSTART"]?.value?.let(::localDateTime)
        if (summary == null || url == null || start == null) {
            logger.warn { "Kallasch& calendar entry '$summary' lacks a title, URL or start, skipping" }
            return emptyList()
        }
        val eventType = eventType(entry)
        val title = if (eventType == EventType.OTHER.name) summary else summary.replace(CATEGORY_PREFIX, "").blankToNull() ?: summary
        val duration = duration(entry, start)
        val description = entry["DESCRIPTION"]?.text?.let(::htmlParagraphText)
        val dates = occurrenceDates(entry, start.toLocalDate(), title)
        val recurring = entry.containsKey("RRULE") || entry.containsKey("RDATE")
        val slug = extractEventSlug(url, PATH_PREFIX)
        val artists = if (eventType == EventType.CONCERT.name && !recurring) headlinersFromTitle(title) else emptyList<ScrapedArtist>()
        return dates.map { date ->
            val end = duration?.let { date.atTime(start.toLocalTime()).plus(it) }
            ScrapedEvent(
                title = title,
                description = description,
                eventType = eventType,
                eventDate = date,
                startTime = start.toLocalTime(),
                endDate = end?.toLocalDate(),
                endTime = end?.toLocalTime(),
                imageUrl = entry["ATTACH"]?.takeIf { it.params["FMTTYPE"]?.startsWith("image/") == true }?.value,
                sourceUrl = if (recurring) "$url?occurrence=$date" else url,
                sourceId = "${EventSource.KALLASCH.sourceIdPrefix}$slug" + if (recurring) "-$date" else "",
                free = hasFreeEntryPhrase(description),
                artists = artists
            )
        }
    }

    /** The type the entry's first category names, `OTHER` for a format such as the open mic. */
    private fun eventType(entry: Map<String, CalendarProperty>): String =
        entry["CATEGORIES"]
            ?.text
            ?.let(::decodeHtmlEntities)
            ?.substringBefore(',')
            ?.blankToNull()
            ?.let { CATEGORY_TYPES[it.lowercase()] } ?: EventType.OTHER.name

    /** How long a night lasts from `DTSTART` to `DTEND`; `null` without a later end. */
    private fun duration(
        entry: Map<String, CalendarProperty>,
        start: LocalDateTime
    ): Duration? =
        entry["DTEND"]
            ?.value
            ?.let(::localDateTime)
            ?.let { Duration.between(start, it) }
            ?.takeIf { !it.isNegative && !it.isZero }

    /** The series' dates from today to the horizon: `DTSTART`, its `RRULE` and its `RDATE`s, minus its `EXDATE`s. */
    private fun occurrenceDates(
        entry: Map<String, CalendarProperty>,
        start: LocalDate,
        title: String
    ): List<LocalDate> {
        val today = LocalDate.now(clock)
        val limit = today.plusWeeks(HORIZON_WEEKS)
        val ruleDates =
            entry["RRULE"]?.value?.let { rule ->
                recurrenceDates(rule, start, limit) ?: listOf(start).also {
                    logger.warn { "Kallasch& series '$title' repeats by '$rule', which is not expanded; keeping its start date only" }
                }
            } ?: listOf(start)
        val extraDates = entry["RDATE"]?.value.dateList().filter { it <= limit }
        val skipped = entry["EXDATE"]?.value.dateList().toSet()
        return (ruleDates + extraDates).distinct().sorted().filter { it >= today && it !in skipped }
    }

    /** A comma-separated `RDATE` or `EXDATE` value as Berlin dates; a `Z` time is UTC. */
    private fun String?.dateList(): List<LocalDate> =
        orEmpty().split(',').mapNotNull { it.blankToNull() }.map { value ->
            if (value.endsWith('Z')) {
                LocalDateTime
                    .parse(value.removeSuffix("Z"), DATE_TIME)
                    .atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(BERLIN)
                    .toLocalDate()
            } else {
                LocalDate.parse(value.take(BASIC_DATE_LENGTH), DateTimeFormatter.BASIC_ISO_DATE)
            }
        }

    /** A `TZID=Europe/Berlin` wall-clock time, the only zone the feed declares; a bare date is midnight. */
    private fun localDateTime(value: String): LocalDateTime =
        value.trim().let { raw ->
            if (raw.length == BASIC_DATE_LENGTH) LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay() else LocalDateTime.parse(raw, DATE_TIME)
        }

    private companion object {
        /** How far ahead a series is expanded, the window the Elfsight venues use. */
        const val HORIZON_WEEKS = 26L
        const val PATH_PREFIX = "/programm/"
        const val BASIC_DATE_LENGTH = 8
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** The `<CATEGORY> // ` head every title carries. */
        val CATEGORY_PREFIX = Regex("""^[^/]+?\s*//\s*""")

        val CATEGORY_TYPES =
            mapOf(
                "konzert" to EventType.CONCERT.name,
                "quiz" to EventType.QUIZ.name,
                "poetry slam" to EventType.READING.name,
                "lesung" to EventType.READING.name
            )
    }
}
