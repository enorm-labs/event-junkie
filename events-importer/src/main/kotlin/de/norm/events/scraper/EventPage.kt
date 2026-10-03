package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.at(Level.WARN) {
            message = "Failed to read the event page for '${event.title}', keeping listing data"
            cause = e
            payload = mapOf(LogFields.URL to event.sourceUrl, LogFields.EVENT_SOURCE_ID to event.sourceId)
        }
        null
    }

/**
 * [events] enriched from the pages they share: a performance listing whose rows link one page per
 * production. Each distinct [key] (the page URL by default) is fetched once, through its first row,
 * with [readEventPage]; [apply] copies the page onto every row that shares it. A page that fails or
 * parses to null flags every one of its rows, as [withEventPageOrFlagged] flags one (#2518).
 */
suspend fun <T : Any> HtmlFetcher.enrichFromSharedPages(
    events: List<ScrapedEvent>,
    parse: (Document) -> T?,
    apply: (T, ScrapedEvent) -> ScrapedEvent,
    pageOwnsImage: Boolean = false,
    key: (ScrapedEvent) -> Any = ScrapedEvent::sourceUrl
): List<ScrapedEvent> {
    val groups = events.groupBy(key)
    logger.info { "Fetching ${groups.size} shared page(s) for ${events.size} event(s)" }
    val pages = groups.mapValues { (_, rows) -> readEventPage(rows.first(), parse) }
    return events.map { event ->
        pages[key(event)]?.let { apply(it, event) } ?: event.copy(detailUnavailable = true, listingImageStandsIn = pageOwnsImage)
    }
}
