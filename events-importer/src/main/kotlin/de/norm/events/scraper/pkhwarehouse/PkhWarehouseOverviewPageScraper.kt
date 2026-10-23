package de.norm.events.scraper.pkhwarehouse

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.collapseWhitespace
import de.norm.events.scraper.endOn
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay

/**
 * Pure parser for the PKH Warehouse home page. The programme section `#s2` holds an `h3` per
 * month (`Oktober`, no year) and a `.zeile` per night below it: the day in `.d`, the title in
 * `.t` with the line-up in `.mit`, and weekday plus times in `.z` (`Fr 22:00 — 04:00`, `Sa ab
 * 22:00`). The month and the weekday pick the year. In the line-up, the text before ` — ` names
 * the crews or the theme and the text after it the DJs, grouped by floor (`Pop Floor: …`).
 */
class PkhWarehouseOverviewPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        var month: Month? = null
        val nights =
            document.select("#s2 h3, #s2 .zeile").mapNotNull { element ->
                if (element.tagName() == "h3") {
                    month = parseMonth(element.text())
                    if (month == null) logger.warn { "PKH Warehouse month heading '${element.text()}' is not a month, skipping its nights" }
                    null
                } else {
                    month?.let { element to it }
                }
            }
        logger.info { "Found ${nights.size} night(s) on the PKH Warehouse page" }
        return nights.mapSkippingFailures(logger, "a PKH Warehouse night") { (row, rowMonth) -> parseNight(row, rowMonth, sourceUrl) }
    }

    private fun parseNight(
        row: Element,
        month: Month,
        sourceUrl: String
    ): ScrapedEvent? {
        val day = row.selectFirst(".d")?.text().orEmpty()
        val times =
            TIME_CELL.matchEntire(
                row
                    .selectFirst(".z")
                    ?.text()
                    ?.collapseWhitespace()
                    .orEmpty()
            )
        val date = parseDate(day, month, times?.groupValues?.get(WEEKDAY_GROUP))
        if (date == null) {
            logger.warn { "PKH Warehouse night with day '$day' in $month has no readable date, skipping" }
            return null
        }
        val title =
            row
                .selectFirst(".t")
                ?.ownText()
                ?.collapseWhitespace()
                .blankToNull()
        if (title == null) {
            logger.warn { "PKH Warehouse night on $date has no title, skipping" }
            return null
        }
        val lineup = row.selectFirst(".t .mit")
        val lineupText =
            lineup
                ?.text()
                ?.collapseWhitespace()
                ?.removeSuffix(TICKETS_LINK)
                ?.trim(' ', '·')
                .blankToNull()
        val start = times?.let { parseTime("${it.groupValues[START_HOUR_GROUP]}:${it.groupValues[START_MINUTE_GROUP]}") }
        val end =
            times
                ?.groupValues
                ?.get(END_GROUP)
                ?.blankToNull()
                ?.let(::parseTime)
        return ScrapedEvent(
            title = title,
            subtitle = lineupText?.let(::theme),
            description = lineupText,
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.PKH_WAREHOUSE.sourceIdPrefix}$date",
            ticketUrl = lineup?.selectFirst("a[href]")?.absUrl("href").blankToNull(),
            artists = lineupText?.let(::djs).orEmpty().map { ScrapedArtist(name = it, role = "DJ") }
        )
    }

    private fun parseMonth(heading: String): Month? =
        GERMAN_MONTH_NAMES
            .indexOfFirst { it.equals(heading.collapseWhitespace(), ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.let { Month.of(it + 1) }

    private fun parseDate(
        day: String,
        month: Month,
        weekday: String?
    ): LocalDate? {
        val dayOfMonth = day.trim().toIntOrNull() ?: return null
        return try {
            inferYearForWeekday(MonthDay.of(month, dayOfMonth), parseGermanWeekdayAbbreviation(weekday), clock)
        } catch (_: DateTimeException) {
            null
        }
    }

    private companion object {
        /** `Fr 22:00 — 04:00` or `Sa ab 22:00`. */
        val TIME_CELL = Regex("""^(\p{L}{2})\.?\s+(?:ab\s+)?(\d{1,2}):(\d{2})(?:\s*[—–-]\s*(\d{1,2}:\d{2}))?$""", RegexOption.IGNORE_CASE)
        const val WEEKDAY_GROUP = 1
        const val START_HOUR_GROUP = 2
        const val START_MINUTE_GROUP = 3
        const val END_GROUP = 4

        const val TICKETS_LINK = "Tickets"
    }
}

/** The crews or theme a line-up names before its ` — `, or `null` when it names none. */
private fun theme(lineup: String): String? =
    lineup
        .split(LINEUP_DASH, limit = 2)
        .takeIf { it.size == 2 }
        ?.first()
        .blankToNull()

/**
 * The DJs of a line-up: the text after ` — `, or the whole line-up when it has no dash but lists
 * names with commas. A single phrase without either (`1. Festival of Lights Clubnight`) is a
 * theme as often as a name, so it yields none. A [FLOOR_LABEL] is dropped.
 */
private fun djs(lineup: String): List<String> {
    val parts = lineup.split(LINEUP_DASH, limit = 2)
    val names =
        when {
            parts.size == 2 -> parts[1]
            ',' in lineup -> lineup
            else -> return emptyList()
        }
    return names
        .split(GROUP_SEPARATOR)
        .flatMap { it.replaceFirst(FLOOR_LABEL, "").split(',') }
        .mapNotNull { it.blankToNull() }
        .filterNot(::isNonArtistName)
        .distinct()
}

private val LINEUP_DASH = Regex("""\s+[—–]\s+""")
private val GROUP_SEPARATOR = Regex("""\s+·\s+""")

/** `Pop Floor: `, a label and a spaced colon; `Sin:port` is a name. */
private val FLOOR_LABEL = Regex("""^[^,:]+:\s+""")
