package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

private val logger = KotlinLogging.logger {}

/**
 * [event] enriched from its own page by [enrich], or [event] flagged [ScrapedEvent.detailUnavailable]
 * when the page yields nothing. The flag makes the upsert keep the fields the page stored last time.
 * [eventPageOwnsImage] marks the listing's image a stand-in, so a stored one wins over it (#2465).
 * An importer that fetches event pages calls this, or [AbstractSinglePageWebsiteImporter.enrichFromEventPage].
 */
suspend fun HtmlFetcher.withEventPageOrFlagged(
    event: ScrapedEvent,
    eventPageOwnsImage: Boolean = false,
    enrich: (Document) -> ScrapedEvent?
): ScrapedEvent = readEventPage(event, enrich) ?: event.copy(detailUnavailable = true, listingImageStandsIn = eventPageOwnsImage)

/**
 * [event]'s own page read by [parse], or null when the fetch or the parse fails or [parse] returns null.
 * A failure logs one `WARN` with the page URL and the event's source id. Use it only where a parsed
 * page can still drop the row; [withEventPageOrFlagged] covers every other case.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: one broken event page must not fail the whole import
suspend fun <T : Any> HtmlFetcher.readEventPage(
    event: ScrapedEvent,
    parse: (Document) -> T?
): T? =
    try {
        val document = fetchDocument(event.sourceUrl)
        // After the fetch: the fetch and the WARN below write `url` as a payload, and a line cannot carry it twice (#982).
        withContext(LogContext.forPage(event.sourceUrl)) { parse(document) }
    } catch (e: Exception) {
        logger.at(Level.WARN) {
            message = "Failed to read the event page for '${event.title}', keeping listing data"
            cause = e
            payload = mapOf(LogFields.URL to event.sourceUrl, LogFields.EVENT_SOURCE_ID to event.sourceId)
        }
        null
    }
