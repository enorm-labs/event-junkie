package de.norm.events.scraper.loge

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure parser for Loge event detail pages (`/event-details/<slug>`).
 *
 * Each page carries a `<script type="application/ld+json">` schema.org `Event` block — the most
 * stable source on the page (ADR-007 §"Selector Strategy" priority 1). **Primary** for the
 * ticket price (`offers`); confirms title, Berlin-local date/start time (from the offset-aware
 * `startDate`) and status. No artist roster is rendered, so `artists` are left to the overview
 * via [LogeWebsiteImporter.fillGapsFromOverview].
 *
 * @see LogeOverviewPageScraper for overview parsing (discovery, artists, fallback).
 * @see LogeWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.loge-berlin.org/event-details/estamoe-daloy-furie">Example detail page</a>
 */
class LogeDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without parseable schema.org `Event`
     * JSON-LD or a title.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing JSON-LD and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val event = document.jsonLdEvents().firstOrNull()
        if (event == null) {
            logger.warn { "Detail page has no schema.org Event JSON-LD, skipping" }
            return null
        }
        val title = event.schemaName()
        if (title == null) {
            logger.warn { "Detail page has no event name, skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // Detail pages always carry the real date; sentinel only if absent (then fillGapsFromOverview).
            eventDate = event.schemaDate("startDate") ?: UNRESOLVED_EVENT_DATE,
            startTime = event.schemaTime("startDate"),
            imageUrl = event.schemaImageUrl(),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.LOGE.sourceIdPrefix}${extractEventSlug(sourceUrl, "/event-details/")}",
            pricePresale = parsePresalePrice(event.path("offers")),
            status = event.schemaStatus()
        )
    }

    /**
     * The presale price from the schema.org `offers` node. Loge sells on-site through Wix, so a
     * single ticket type is normal; the [AggregateOffer][https://schema.org/AggregateOffer]'s
     * `lowPrice` (the cheapest ticket, gross of the service fee) is used, falling back to a plain
     * offer's `price` or the first nested `offers[].price`. `null` for a free or price-less event.
     */
    private fun parsePresalePrice(offers: JsonNode): BigDecimal? {
        val candidates =
            listOf(
                offers.stringOrNull("lowPrice"),
                offers.stringOrNull("price"),
                offers.path("offers").firstOrNull()?.stringOrNull("price")
            )
        return candidates.firstNotNullOfOrNull { it?.toBigDecimalOrNull() }
    }
}
