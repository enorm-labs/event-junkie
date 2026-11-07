package de.norm.events.scraper.olympiastadion

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.PADDED_GERMAN_DATE
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.dateOrNull
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure parser for `/termine-und-tickets/`: a WordPress theme whose cards link the `events` post type.
 *
 * Each card prints the title, the day as `30.06` and the year beside it, and a poster in `data-src`.
 * The featured card repeats one of the grid's, so an event is kept once. Hertha's home games are cards
 * of another post type, under `/spiele/`, and are not read; the other sport is dropped on the event page.
 */
class OlympiastadionOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select("a.card-link[href*=$EVENT_PATH]")
        logger.info { "Found ${cards.size} event card(s) on the Olympiastadion listing" }
        return cards
            .mapSkippingFailures(logger, "an Olympiastadion event card") { parseCard(it, sourceUrl) }
            .distinctBy { it.sourceId }
    }

    private fun parseCard(
        card: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = card.textAt("h3") ?: error("No title found")
        val eventDate = parseDate(card.select(".card__date span").joinToString(".") { it.text().trim() })
        if (eventDate == null) {
            logger.warn { "No parseable date for Olympiastadion event '$title', skipping" }
            return null
        }
        val url = card.absUrl("href").ifEmpty { error("No absolute event URL for '$title' on $sourceUrl") }
        val eventType = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            eventType = eventType,
            typeIsFallback = true,
            eventDate = eventDate,
            imageUrl = card.attrAt("img[data-src]", "data-src")?.takeIf { it.startsWith("http") },
            sourceUrl = url,
            sourceId = "${EventSource.OLYMPIASTADION.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH)}",
            artists = buildArtistsForEventType(title, null, eventType)
        )
    }

    private fun parseDate(text: String): LocalDate? = dateOrNull { LocalDate.parse(text, PADDED_GERMAN_DATE) }

    private companion object {
        const val EVENT_PATH = "/events/"
    }
}
