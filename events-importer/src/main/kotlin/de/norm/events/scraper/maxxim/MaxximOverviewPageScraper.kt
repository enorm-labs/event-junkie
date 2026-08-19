package de.norm.events.scraper.maxxim

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WIX_REGISTRATION_TICKETS
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure parser for MAXXIM's Wix Events programme page (`/partys`).
 *
 * Every field comes from the embedded `wix-warmup-data` JSON (see
 * [WixEventsWarmupData]) — the rendered cards are never read. Unlike Loge, whose
 * warmup payload omits the price, MAXXIM's `registration.ticketing` block carries
 * the ticket price and the sold-out flag, so the single overview fetch is
 * complete: no `/event-details/<slug>` page is fetched (its `<slug>` is still
 * used for the canonical [ScrapedEvent.sourceUrl] and the stable
 * [ScrapedEvent.sourceId]).
 *
 * @see MaxximWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.maxxim-berlin.de/partys">MAXXIM programme</a>
 */
class MaxximOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page's embedded Wix warmup payload.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the
     *   per-event `/event-details/<slug>` URLs.
     * @return a list of [ScrapedEvent] instances, one per listed night.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.MAXXIM) ?: return emptyList()
        logger.info { "Found ${events.size()} event(s) in MAXXIM Wix warmup payload" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import
        return events.mapNotNull { node ->
            try {
                parseEvent(node, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse MAXXIM event, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required slug, title and date are clearer than nesting
    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val slug = node.stringOrNull("slug")
        if (slug == null) {
            logger.warn { "MAXXIM event has no slug, skipping" }
            return null
        }
        val title = node.stringOrNull("title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "MAXXIM event '$slug' has no title, skipping" }
            return null
        }
        // Unlike Loge there is no detail page to recover a to-be-decided date from, so an event
        // without a resolvable startDate is dropped rather than persisted with a sentinel date.
        val (eventDate, startTime) = parseWixSchedule(node.path("scheduling").path("config"))
        if (eventDate == null) {
            logger.warn { "MAXXIM event '$slug' has no parseable start date, skipping" }
            return null
        }

        // Every night in the live programme is Wix-ticketed, but the guard costs nothing and keeps a
        // future externally ticketed night from being reported sold out — see [WIX_REGISTRATION_TICKETS].
        val registration = node.path("registration")
        val ticketing = registration.path("ticketing").takeIf { registration.path("type").asInt(0) == WIX_REGISTRATION_TICKETS }
        return ScrapedEvent(
            title = title,
            description = node.stringOrNull("description"),
            // MAXXIM publishes no categories: it is a club whose every night is a DJ dance party.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = resolveUrl(baseUrl, "/event-details/$slug"),
            sourceId = "${EventSource.MAXXIM.sourceIdPrefix}$slug",
            pricePresale = ticketing?.let { parseTicketPrice(it.path("lowestTicketPrice")) },
            priceNote = ticketing?.let { parsePriceRangeNote(it) },
            soldOut = ticketing?.path("soldOut")?.asBoolean(false) == true,
            status = mapWixEventStatus(node.path("status"))
        )
    }

    /**
     * Reads a Wix ticket price node (`{"amount": "12.00", "currency": "EUR"}`).
     * Returns `null` when the amount is absent or not a number — a free or
     * registration-only night carries no ticketing block at all.
     */
    private fun parseTicketPrice(price: JsonNode): BigDecimal? = price.stringOrNull("amount")?.toBigDecimalOrNull()

    /**
     * Builds a price note only when a night has several ticket tiers, i.e. when
     * the lowest and highest formatted prices differ (`"€10 – €25"`). A single-tier
     * night — the normal case — needs no note: [ScrapedEvent.pricePresale] already
     * says everything.
     */
    private fun parsePriceRangeNote(ticketing: JsonNode): String? {
        val lowest = ticketing.stringOrNull("lowestTicketPriceFormatted")
        val highest = ticketing.stringOrNull("highestTicketPriceFormatted")
        return if (lowest != null && highest != null && lowest != highest) "$lowest – $highest" else null
    }
}
