package de.norm.events.scraper.kallasch

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.CalendarProperty
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ICAL_LOCAL_DATE_TIME
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
import de.norm.events.scraper.siteTime
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
 * series, not an act, so only a one-off concert mints artists. A `MITSINGKONZERT //` night is filed
 * as `KONZERT` but names its sing-along series (`Montagsmucke am Freitag`), so it mints none either.
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
        val start = entry["DTSTART"]?.siteTime()?.dateTime
        if (summary == null || url == null || start == null) {
            logger.warn { "Kallasch& calendar entry '$summary' lacks a title, URL or start, skipping" }
            return emptyList()
        }
        val category = category(entry)
        val eventType = category?.let(CATEGORY_TYPES::get) ?: EventType.OTHER.name
        val title = titleOf(summary, category, eventType)
        val duration = duration(entry, start)
        val description = entry["DESCRIPTION"]?.text?.let(::htmlParagraphText)
        val dates = occurrenceDates(entry, start.toLocalDate(), title)
        val recurring = entry.containsKey("RRULE") || entry.containsKey("RDATE")
        val slug = extractEventSlug(url, PATH_PREFIX)
        val billsAct = eventType == EventType.CONCERT.name && category != OPEN_MIC && !recurring && !SING_ALONG_HEAD.containsMatchIn(summary)
        val artists = if (billsAct) headlinersFromTitle(title) else emptyList<ScrapedArtist>()
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

    /** The summary without its `KONZERT // ` head where the category types the night; the open mic keeps the head that names it. */
    private fun titleOf(
        summary: String,
        category: String?,
        eventType: String
    ): String = if (eventType == EventType.OTHER.name || category == OPEN_MIC) summary else summary.replace(CATEGORY_PREFIX, "").blankToNull() ?: summary

    /** The entry's first category, lowercased: `konzert`, `open mic`. */
    private fun category(entry: Map<String, CalendarProperty>): String? =
        entry["CATEGORIES"]
            ?.text
            ?.let(::decodeHtmlEntities)
            ?.substringBefore(',')
            ?.blankToNull()
            ?.lowercase()

    /** How long a night lasts, from `DTSTART` to `DTEND`, else its `DURATION` (the jazz series'); `null` without a later end. */
    private fun duration(
        entry: Map<String, CalendarProperty>,
        start: LocalDateTime
    ): Duration? {
        val end = entry["DTEND"]?.siteTime()?.dateTime
        val length = if (end != null) Duration.between(start, end) else entry["DURATION"]?.value?.let(::parseDuration)
        return length?.takeIf { !it.isNegative && !it.isZero }
    }

    private fun parseDuration(value: String): Duration? =
        runCatching { Duration.parse(value.trim()) }
            .onFailure { logger.warn(it) { "Kallasch& DURATION '$value' does not parse, keeping no end" } }
            .getOrNull()

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
                    .parse(value.removeSuffix("Z"), ICAL_LOCAL_DATE_TIME)
                    .atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(BERLIN)
                    .toLocalDate()
            } else {
                LocalDate.parse(value.take(BASIC_DATE_LENGTH), DateTimeFormatter.BASIC_ISO_DATE)
            }
        }

    private companion object {
        /** How far ahead a series is expanded, the window the Elfsight venues use. */
        const val HORIZON_WEEKS = 26L
        const val PATH_PREFIX = "/programm/"
        const val BASIC_DATE_LENGTH = 8

        /** The `<CATEGORY> // ` head every title carries. */
        val CATEGORY_PREFIX = Regex("""^[^/]+?\s*//\s*""")

        /** The `MITSINGKONZERT //` head of a sing-along series, led by the house's guitarist. */
        val SING_ALONG_HEAD = Regex("""^\s*mitsing""", RegexOption.IGNORE_CASE)

        /** The open stage: a concert with no act (EVENT_SCOPE.md §2), whose title keeps the `OPEN MIC //` head that names it. */
        const val OPEN_MIC = "open mic"

        val CATEGORY_TYPES =
            mapOf(
                "konzert" to EventType.CONCERT.name,
                OPEN_MIC to EventType.CONCERT.name,
                "quiz" to EventType.QUIZ.name,
                "poetry slam" to EventType.READING.name,
                "lesung" to EventType.READING.name
            )
    }
}
