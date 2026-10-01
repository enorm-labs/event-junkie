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

    /** Runs over the parsed events before they are returned; a venue that enriches them overrides it. */
    protected open suspend fun postProcess(events: List<ScrapedEvent>): List<ScrapedEvent> = events

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
                    events = postProcess(events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}
