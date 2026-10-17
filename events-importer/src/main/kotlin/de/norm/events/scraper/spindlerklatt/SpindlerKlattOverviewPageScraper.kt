package de.norm.events.scraper.spindlerklatt

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parser for the club page's Wix repeater. Each item holds a flyer, a date line
 * (`Fr., 30.10.2026 | 22:00 Uhr`, sometimes `19:00 - 23:00 Uhr`), a title and a "Read More" button
 * that links the night's ticket shop or promoter. The date line is split across spans, so it is
 * read as one text. The printed weekday is not checked: the date carries the year, and one item
 * prints the wrong weekday. A title broken by `<br>` is joined into one line.
 */
class SpindlerKlattOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".wixui-repeater__item")
        logger.info { "Found ${items.size} night(s) on the Spindler & Klatt club page" }
        return items.mapSkippingFailures(logger, "a Spindler & Klatt night") { parseNight(it, sourceUrl) }
    }

    private fun parseNight(
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val texts = item.select("[data-testid=richTextElement]")
        val dateLine = texts.firstOrNull { DATE_LINE.containsMatchIn(it.text()) }
        val match = dateLine?.let { DATE_LINE.find(it.text().replace(WHITESPACE, " ")) }
        val date = match?.let { parseGermanDate(it.groupValues[DATE_GROUP]) }
        if (date == null) {
            logger.warn { "Spindler & Klatt item '${item.id()}' has no parseable date, skipping" }
            return null
        }
        val title =
            texts
                .firstOrNull { it != dateLine }
                ?.textLines()
                ?.joinToString(" ")
                ?.replace(WHITESPACE, " ")
                .blankToNull()
        if (title == null) {
            logger.warn { "Spindler & Klatt night on $date has no title, skipping" }
            return null
        }
        val start = parseTime(match.groupValues[START_GROUP])
        val end = parseTime(match.groupValues[END_GROUP])
        return ScrapedEvent(
            title = title,
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            imageUrl = item.imgSrcAt(".wixui-image img")?.substringBefore(WIX_TRANSFORM),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SPINDLER_KLATT.sourceIdPrefix}$date",
            ticketUrl = item.hrefAt("[data-semantic-classname=button] a")
        )
    }

    private companion object {
        /** `Fr., 30.10.2026 | 22:00 Uhr` or `Sa., 20.11.2026 | 19:00 - 23:00 Uhr`. */
        val DATE_LINE = Regex("""(\d{1,2}\.\d{1,2}\.\d{4})\s*\|\s*(\d{1,2}:\d{2})(?:\s*[-–]\s*(\d{1,2}:\d{2}))?""")
        const val DATE_GROUP = 1
        const val START_GROUP = 2
        const val END_GROUP = 3

        /** Wix appends a resize transform to the media URL; the part before it is the original file. */
        const val WIX_TRANSFORM = "/v1/"
    }
}
