package de.norm.events.scraper.schokoladen

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Schokoladen Mitte's Laravel-based event listing.
 *
 * Schokoladen renders all upcoming events on a single page (`/`) with full
 * details expanded inline (times, descriptions, ticket links, images). Events
 * are addressed only by page fragment (`#e20260711`), so no separate detail
 * pages exist — the pipeline is a single HTTP request per import cycle:
 * 1. Fetch the overview page via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified).
 * 2. Parse all events from the single page via [SchokoladenOverviewPageScraper].
 *
 * @see SchokoladenOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.schokoladen-mitte.de/">Schokoladen Mitte</a>
 */
@Component
class SchokoladenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SCHOKOLADEN

    private val overviewPageScraper = SchokoladenOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Schokoladen" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

/** Nothing this source withholds needs declaring (#715). */
val SCHOKOLADEN_LIMITATIONS = VenueLimitations(EventSource.SCHOKOLADEN)
