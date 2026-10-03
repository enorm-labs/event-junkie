package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Base class for a venue whose whole programme is one listing page: fetch it conditionally, parse it
 * with [scrape], return the events with the page's validators. The one-page counterpart of
 * [AbstractTwoPageWebsiteImporter]; a venue that needs more than one fetch per run implements
 * [EventImporter] directly.
 *
 * [venueName] is how the log line names the source, kept as each venue wrote it.
 */
abstract class AbstractSinglePageWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    private val venueName: String,
    private val scrape: (Document, String) -> List<ScrapedEvent>
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val logger = KotlinLogging.logger(javaClass.name)

    /**
     * Reads one event's own page into the event, or returns null when the page yields nothing.
     * Null for a venue whose listing is the whole record. When set, every event's page is fetched
     * through [withEventPageOrFlagged], which owns the failure path and the flag.
     */
    protected open val enrichFromEventPage: ((ScrapedEvent, Document) -> ScrapedEvent?)? = null

    /** Whether a good run stores the event page's image over the listing's, see [withEventPageOrFlagged]. */
    protected open val eventPageOwnsImage: Boolean = false

    /** A run that reads event pages cannot be skipped on the listing's validators. */
    override val fetchesBeyondEntryPage: Boolean get() = enrichFromEventPage != null

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from $venueName" }

                ImportResult.Success(
                    events = withEventPages(events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    private suspend fun withEventPages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val enrich = enrichFromEventPage ?: return events
        return events.map { event -> htmlFetcher.withEventPageOrFlagged(event, eventPageOwnsImage) { document -> enrich(event, document) } }
    }
}
