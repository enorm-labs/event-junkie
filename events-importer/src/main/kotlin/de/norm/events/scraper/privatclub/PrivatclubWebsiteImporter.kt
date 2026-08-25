package de.norm.events.scraper.privatclub

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Privatclub Berlin's WordPress-based event listing.
 *
 * Privatclub renders all upcoming events on a single page with full details
 * expanded inline (descriptions, prices, ticket links, promoters). Unlike
 * Cassiopeia, no separate detail page fetching is needed — the pipeline is:
 * 1. Fetch the overview page (`/`) via [HtmlFetcher] with conditional
 *    request support (ETag / Last-Modified).
 * 2. Parse all events from the single page via [PrivatclubOverviewPageScraper].
 *
 * This keeps the importer simple: one HTTP request per import cycle.
 *
 * @see PrivatclubOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://privatclub-berlin.de/">Privatclub Berlin</a>
 */
@Component
class PrivatclubWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.PRIVATCLUB

    private val overviewPageScraper = PrivatclubOverviewPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = overviewPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from Privatclub" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

/** Nothing this source withholds needs declaring (#715). */
val PRIVATCLUB_LIMITATIONS = VenueLimitations(EventSource.PRIVATCLUB)
