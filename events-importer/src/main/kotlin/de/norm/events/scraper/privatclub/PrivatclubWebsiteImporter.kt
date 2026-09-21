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
 * Website importer for Privatclub Berlin's WordPress event listing.
 *
 * One page holds every upcoming event with its details inline, so unlike Cassiopeia
 * there is no detail-page fetch: [HtmlFetcher] fetches `/` conditionally
 * (ETag / Last-Modified), [PrivatclubOverviewPageScraper] parses it. One HTTP
 * request per import cycle.
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
