package de.norm.events.scraper.binuu

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Pure parser for Bi Nuu's event listing (overview) page (`/de/events`).
 *
 * Bi Nuu is a SvelteKit/PocketBase site whose listing embeds every event as a
 * JS object literal under `data.events[]` in the page bootstrap script (see
 * [BinuuSvelteKitPayload]). This overview payload is used for **discovery** —
 * each entry yields the event id (hence the detail URL and stable `sourceId`)
 * plus enough fields (title, date, image, sold-out, status) to serve as a
 * fallback when a detail page cannot be fetched. The richer per-event data
 * (doors, description, tickets, promoters, performers) comes from the detail
 * page via [BinuuDetailPageScraper].
 *
 * @see BinuuDetailPageScraper for the primary per-event data source.
 * @see BinuuWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://binuu.de/de/events">Bi Nuu event listing</a>
 */
class BinuuOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the overview page's embedded `data.events[]` payload.
     *
     * @param baseUrl the URL the document was fetched from, used to build per-event
     *   detail URLs.
     * @return a list of discovery [ScrapedEvent] instances, one per listed event.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = BinuuSvelteKitPayload.dataNode(document, "events", '[')
        if (events == null || !events.isArray) {
            logger.warn { "No Bi Nuu events payload found on overview page" }
            return emptyList()
        }
        logger.info { "Found ${events.size()} event(s) in Bi Nuu overview payload" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import
        return events.mapNotNull { node ->
            try {
                parseEvent(node, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Bi Nuu overview event, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required id and title are clearer than nesting
    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val id = node.stringOrNull("id") ?: node.stringOrNull("dbId")
        if (id == null) {
            logger.warn { "Bi Nuu overview event has no id, skipping" }
            return null
        }
        val title = node.stringOrNull("title")
        if (title == null) {
            logger.warn { "Bi Nuu overview event '$id' has no title, skipping" }
            return null
        }

        val start = node.stringOrNull("start")
        val subtitle = node.stringOrNull("subtitle")
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            // Type is inferred (Bi Nuu has no category field). The overview carries
            // only title/subtitle — no blurb — so this is a signal-poor fallback used
            // when a detail page can't be fetched; the detail scraper's richer text wins.
            eventType = inferBinuuEventType(title, subtitle),
            // Fall back to the sentinel if a listing entry ever lacks a date; the
            // detail page then supplies it via BinuuWebsiteImporter.fillGapsFromOverview.
            eventDate = parseBinuuDate(start) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseBinuuTime(start),
            imageUrl = node.binuuImageUrl(),
            sourceUrl = binuuDetailUrl(baseUrl, id),
            sourceId = "${EventSource.BINUU.sourceIdPrefix}$id",
            soldOut = node.path("soldout").asBoolean(),
            status = mapBinuuStatus(node.stringOrNull("eventStatus"))
        )
    }
}

/**
 * Builds the canonical detail-page URL for an event id relative to the listing
 * [baseUrl] (e.g. `https://binuu.de/de/events` + `zf0kroyf2cjolyl` →
 * `https://binuu.de/de/events/zf0kroyf2cjolyl`), matching the site's own
 * `…/de/events/<id>` links.
 */
internal fun binuuDetailUrl(
    baseUrl: String,
    id: String
): String = "${baseUrl.trimEnd('/')}/$id"
