package de.norm.events.scraper.pfefferberghaus13

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
import java.time.MonthDay

/**
 * Pure parser for the Pfefferberg Haus 13 listing, an Event Organiser archive (`div.event-archive`) of five events a page.
 *
 * A row prints the day and the month but no year, which is the occurrence nearest today. Its one time is the start, or
 * the doors when the event page names no start. Below the teaser sits a ticket link, `Abendkasse` or `Freier Eintritt`.
 */
class PfefferbergHaus13OverviewPageScraper(
    private val clock: Clock = Clock.systemDefaultZone()
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
        val title = cleanEventTitle(link.text())
        val month = requireNotNull(parseGermanMonthAbbreviation(row.textAt(".event-arc-month"))) { "'$title' has no readable month" }
        val day = requireNotNull(row.textAt(".event-arc-day")?.toIntOrNull()) { "'$title' has no readable day" }
        val type = eventTypeOf(title)
        return ScrapedEvent(
            title = title,
            eventType = type,
            eventDate = inferYearForWeekday(MonthDay.of(month, day), weekday = null, clock = clock),
            startTime = parseTime(row.textAt(".event-arc-time")),
            imageUrl = row.selectFirst(".event-arc-cover img")?.absUrl("src")?.let(::fullSizeImage),
            sourceUrl = url,
            sourceId = sourceIdOf(url),
            ticketUrl = row.selectFirst(".event-tickets a[href]")?.absUrl("href"),
            free = isFreeEntry(row.textAt(".event-tickets-pw-fix")),
            artists = actsOf(title, type, lineup = emptyList())
        )
    }
}

internal fun sourceIdOf(url: String): String = "${EventSource.PFEFFERBERG_HAUS_13.sourceIdPrefix}${extractEventSlug(url, "/event/")}"
