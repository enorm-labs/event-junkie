package de.norm.events.scraper.binuu

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Pure parser for Bi Nuu's `/de/events` listing, a SvelteKit/PocketBase site embedding every
 * event as a JS object literal under `data.events[]` in the bootstrap script
 * ([BinuuSvelteKitPayload]). Discovery: each entry yields the id (hence the detail URL and
 * `sourceId`) plus title, date, image, sold-out and status as fallback; doors, description,
 * tickets, promoters and performers come from [BinuuDetailPageScraper].
 *
 * @see BinuuDetailPageScraper for the primary per-event data source.
 * @see BinuuWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://binuu.de/de/events">Bi Nuu event listing</a>
 */
class BinuuOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the embedded `data.events[]`.
     *
     * @param baseUrl the URL the document was fetched from, to build detail URLs.
     * @return one discovery [ScrapedEvent] per listed event.
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
            // Type inferred (no category field) from title/subtitle only, a signal-poor fallback; the detail
            // scraper's richer text wins.
            eventType = inferBinuuEventType(title, subtitle),
            // The sentinel if a listing entry lacks a date; the detail page supplies it.
            eventDate = parseBinuuDate(start) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseBinuuTime(start),
            imageUrl = node.binuuImageUrl(),
            sourceUrl = binuuDetailUrl(baseUrl, id),
            sourceId = "${EventSource.BINUU.sourceIdPrefix}$id",
            soldOut = node.path("soldout").asBoolean(),
            status = mapBinuuStatus(node.stringOrNull("eventStatus"), node.stringOrNull("startOld")),
            statusNote = binuuRelocationNote(node.stringOrNull("eventStatus"), node.stringOrNull("locationNew"), node.stringOrNull("locationArticle"))
        )
    }
}

/**
 * The detail URL for an id relative to [baseUrl] (`https://binuu.de/de/events` +
 * `zf0kroyf2cjolyl`), matching the site's `…/de/events/<id>` links.
 */
private fun binuuDetailUrl(
    baseUrl: String,
    id: String
): String = "${baseUrl.trimEnd('/')}/$id"
