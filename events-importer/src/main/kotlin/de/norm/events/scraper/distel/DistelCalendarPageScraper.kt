package de.norm.events.scraper.distel

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for one month of the DISTEL calendar (`/spielplan/kalender/?month=YYYYMM`).
 *
 * Each `div.event_list` is one performance: the day, a `Sa<br>Okt` weekday-and-month line, the
 * clock, the show's title and subtitle, and a link into the ticket shop. The shop's `event` id is
 * empty until presale opens, so a performance is keyed by show, date and clock. The year comes
 * from the month navigation's current entry. Availability is a class on `div.available`: `sold_out` when no seat is left.
 *
 * A touring date names its town and hall in the subtitle (`Aurich・Stadthalle Aurich`) on the show
 * pages; the calendar lists the home stage only, and a row with that marker is skipped.
 */
class DistelCalendarPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val heading = parseHeading(document)
        val items = document.select("div.event.event_list")
        logger.info { "Found ${items.size} DISTEL performance(s) on $baseUrl" }
        if (heading == null) return emptyList()

        return items.mapSkippingFailures(logger, "DISTEL performance") { item ->
            parseItem(item, heading)
        }
    }

    /** True when the month lists no performance, which ends the walk. */
    fun isEmptyMonth(document: Document): Boolean = document.select("div.event.event_list").isEmpty()

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun parseItem(
        item: Element,
        heading: Pair<Month, Int>
    ): ScrapedEvent? {
        val link = item.selectFirst(".title a[href]") ?: return null
        val title = link.text().trim().takeIf { it.isNotEmpty() } ?: return null
        val subtitle = item.textAt(".subtitle")
        if (subtitle != null && TOURING_MARKER in subtitle) return null
        val eventDate = parseDate(item, heading) ?: return null
        val startTime = parseTime(item.textAt(".time"))
        val sourceUrl = link.absUrl("href")
        val slug = sourceUrl.trimEnd('/').substringAfterLast('/')
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = EventType.COMEDY.name,
            eventDate = eventDate,
            startTime = startTime,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.DISTEL.sourceIdPrefix}$slug-$eventDate-${startTime?.format(HHMM).orEmpty()}",
            ticketUrl = item.selectFirst(".ce_button a[href*=event=]")?.absUrl("href")?.takeUnless { it.endsWith("event=") },
            soldOut = item.selectFirst(".available.sold_out") != null
        )
    }

    /** The day, and the month from the `Sa<br>Okt` line; a January row on a December page is next year. */
    private fun parseDate(
        item: Element,
        heading: Pair<Month, Int>
    ): LocalDate? {
        val day = item.textAt(".day")?.toIntOrNull() ?: return null
        val month = item.selectFirst(".weekday")?.let { parseGermanMonthAbbreviation(it.ownText().split(' ').last()) } ?: heading.first
        val year = if (month < heading.first) heading.second + 1 else heading.second
        return LocalDate.of(year, month, day)
    }

    /** The month navigation's current entry: `<span class="month">Oktober</span> <span class="year">2026</span>`. */
    private fun parseHeading(document: Document): Pair<Month, Int>? {
        val current = document.selectFirst(".month_navigation .current")
        val month = GERMAN_MONTHS[current?.textAt(".month")?.lowercase()]
        val year = current?.textAt(".year")?.toIntOrNull()
        return if (month != null && year != null) month to year else null
    }

    private companion object {
        const val TOURING_MARKER = "・"
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        val GERMAN_MONTHS: Map<String, Month> =
            listOf(
                "januar",
                "februar",
                "märz",
                "april",
                "mai",
                "juni",
                "juli",
                "august",
                "september",
                "oktober",
                "november",
                "dezember"
            ).mapIndexed { index, name -> name to Month.of(index + 1) }.toMap()
    }
}
