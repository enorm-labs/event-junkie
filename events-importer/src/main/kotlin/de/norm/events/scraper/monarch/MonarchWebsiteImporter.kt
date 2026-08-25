package de.norm.events.scraper.monarch

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Monarch Berlin's retro `programm.php` event listing.
 *
 * Monarch renders its whole programme on a single hand-coded PHP page with no
 * per-event detail pages, so the pipeline is a single request per cycle:
 * 1. Fetch the programme page via [HtmlFetcher] with conditional request support
 *    (ETag / Last-Modified).
 * 2. Parse all events from the single page via [MonarchOverviewPageScraper].
 *
 * @see MonarchOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://kottimonarch.de/programm.php">Monarch programme</a>
 */
@Component
class MonarchWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MONARCH

    private val overviewPageScraper = MonarchOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Monarch" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val MONARCH_LIMITATIONS =
    VenueLimitations(
        EventSource.MONARCH,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site is hand-coded PHP with no per-event URLs")
    )
