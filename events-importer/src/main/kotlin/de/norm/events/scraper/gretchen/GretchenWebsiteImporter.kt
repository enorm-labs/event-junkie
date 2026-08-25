package de.norm.events.scraper.gretchen

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Gretchen Berlin's retro hand-coded event listing.
 *
 * Gretchen renders all upcoming events on a single homepage (`/`) as `.gig`
 * blocks with full details inline (date, times, genre, lineup, prices,
 * promoter, image). Like Privatclub and Frannz, no separate detail-page fetch
 * is needed — the pipeline is:
 * 1. Fetch the overview page (`/`) via [HtmlFetcher] with conditional request
 *    support (ETag / Last-Modified).
 * 2. Parse all events from the single page via [GretchenOverviewPageScraper].
 *
 * This keeps the importer simple: one HTTP request per import cycle.
 *
 * @see GretchenOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.gretchen-club.de/">Gretchen Berlin</a>
 */
@Component
class GretchenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.GRETCHEN

    private val overviewPageScraper = GretchenOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Gretchen" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

/** Nothing this source withholds needs declaring (#715). */
val GRETCHEN_LIMITATIONS = VenueLimitations(EventSource.GRETCHEN)
