package de.norm.events.scraper.pfefferberghaus13

import de.norm.events.common.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure parser for the Pfefferberg Haus 13 listing, an Event Organiser archive (`div.event-archive`) of five events a page.
 *
 * A row prints the day and the month but no year, which is the occurrence nearest today. A multi-day row prints its first
 * and last day in `-finish` classes instead, and the last is the end date. Its one time is the start, or
 * the doors when the event page names no start. Below the teaser sits a ticket link, `Abendkasse` or `Freier Eintritt`.
 */
class PfefferbergHaus13OverviewPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("div.event-archive")
        logger.info { "Found ${rows.size} Pfefferberg Haus 13 listing row(s)" }
        return rows.mapSkippingFailures(logger, "Pfefferberg Haus 13 listing row") { row(it, baseUrl) }
    }

    private fun row(
        row: Element,
        baseUrl: String
    ): ScrapedEvent {
        val link = requireNotNull(row.selectFirst("h2.event-arc-title a")) { "Listing row has no title link" }
        val url = resolveUrl(baseUrl, link.attr("href"))
        val cleanedTitle = cleanEventTitle(link.text())
        val title = cleanedTitle.text
        val multiDay = row.selectFirst(".event-arc-month") == null
        val days = row.select(if (multiDay) ".event-arc-day-finish" else ".event-arc-day")
        val months = row.select(if (multiDay) ".event-arc-month-finish" else ".event-arc-month")
        val start = inferYearForWeekday(monthDayOf(days.firstOrNull(), months.firstOrNull(), title), weekday = null, clock = clock)
        val type = eventTypeOf(title)
        return ScrapedEvent(
            title = title,
            eventType = type,
            eventDate = start,
            endDate = if (multiDay) endOf(monthDayOf(days.lastOrNull(), months.lastOrNull(), title), start) else null,
            startTime = parseTime(row.textAt(".event-arc-time")),
            imageUrl = row.selectFirst(".event-arc-cover img")?.absUrl("src")?.let(::fullSizeImage),
            sourceUrl = url,
            sourceId = sourceIdOf(url),
            ticketUrl = row.selectFirst(".event-tickets a[href]")?.absUrl("href"),
            free = isFreeEntry(row.textAt(".event-tickets-pw-fix")),
            soldOut = cleanedTitle.soldOut,
            artists = actsOf(title, type, lineup = emptyList())
        )
    }

    private fun monthDayOf(
        day: Element?,
        month: Element?,
        title: String
    ): MonthDay {
        val monthValue = requireNotNull(parseGermanMonthAbbreviation(month?.text())) { "'$title' has no readable month" }
        val dayValue = requireNotNull(day?.text()?.toIntOrNull()) { "'$title' has no readable day" }
        return MonthDay.of(monthValue, dayValue)
    }

    /** The last day in the year of [start], or the next one for a run over New Year; none when it is the start itself. */
    private fun endOf(
        last: MonthDay,
        start: LocalDate
    ): LocalDate? {
        val end = last.atYear(start.year).let { if (it < start) last.atYear(start.year + 1) else it }
        return end.takeIf { it > start }
    }
}

internal fun sourceIdOf(url: String): String = "${EventSource.PFEFFERBERG_HAUS_13.sourceIdPrefix}${extractEventSlug(url, "/event/")}"
