package de.norm.events.scraper.orania

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.parseClock
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for one page of Orania.Berlin's TYPO3 Calendarize listing (`/concerts`,
 * `/concerts/page/N`). Each `div.event-list-item` prints the day, `Oct 2026`, a 12-hour clock,
 * the series tag (`Orania.Piano`, `Orania.Grooves`), the title and a one-line teaser.
 *
 * An entry without a series tag is a programme notice ("Winter Break", "Holiday Season
 * Special") dated at midnight, not a concert, and is skipped. A title can name the act's
 * programme after it ([PROGRAMME_TAIL]); the artists come from the part before.
 */
class OraniaOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.calendarize div.event-list-item")
        logger.info { "Found ${items.size} Orania listing item(s) on $baseUrl" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip one malformed item without aborting the import
        return items.mapNotNull { item ->
            try {
                parseItem(item)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Orania listing item, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for each required field are clearer than nesting
    private fun parseItem(item: Element): ScrapedEvent? {
        val link = item.selectFirst("h2 a[href]")
        val title = link?.text()?.trim()
        if (link == null || title.isNullOrBlank()) {
            logger.warn { "Orania listing item has no title link, skipping" }
            return null
        }
        if (item.textAt(".event-category") == null) {
            logger.debug { "Skipping Orania programme notice '$title': it carries no series tag" }
            return null
        }
        val eventDate = parseDate(item)
        if (eventDate == null) {
            logger.warn { "Could not parse the date of Orania event '$title', skipping" }
            return null
        }

        val sourceUrl = link.absUrl("href")
        val subtitle = item.textAt(".media-body > p")
        val eventType = EventType.CONCERT.name
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = eventDate,
            startTime = item.textAt(".event-time")?.split(' ')?.let { parseClock(it.first(), it.getOrNull(1)) },
            imageUrl = item.selectFirst(".calendarize-item img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            sourceId = oraniaSourceId(sourceUrl),
            free = true,
            artists = buildArtistsForEventType(title.replace(PROGRAMME_TAIL, ""), subtitle, eventType)
        )
    }

    /** The day (`02`) and the month with its year (`Oct 2026`), printed in two elements. */
    private fun parseDate(item: Element): LocalDate? {
        val text = listOfNotNull(item.textAt(".event-date"), item.textAt(".event-month")).joinToString(" ")
        return try {
            LocalDate.parse(text, DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        /**
         * A programme named after the act: `Rossano Snel Duo presents The Fire In The Forest`,
         * `Rolf Zielke & Hogir Göregen - Piano meets Percussion`. The bill is the part before it.
         */
        val PROGRAMME_TAIL = Regex("""\s+(?:presents|[-–—])\s+.*$""", RegexOption.IGNORE_CASE)

        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}

/** The event page's last path segment, `matti-klein-tayfun-schulzke-20261002`; the CMS appends the date to a repeated booking. */
internal fun oraniaSourceId(url: String): String = "${EventSource.ORANIA.sourceIdPrefix}${URI(url).path.trimEnd('/').substringAfterLast('/')}"
