package de.norm.events.scraper.duncker

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
import java.time.Clock

/**
 * Website importer for Duncker Club Berlin's retro `start.html` programme page.
 *
 * The whole programme lives on a single hand-coded HTML page with no per-event
 * detail pages, so the pipeline is a single fetch:
 * 1. Fetch `start.html` via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified).
 * 2. Parse all events from the page via [DunckerOverviewPageScraper].
 *
 * @see DunckerOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.dunckerclub.de/start.html">Duncker Club programme</a>
 */
@Component
class DunckerWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's past-event cutoff and year inference. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.DUNCKER

    private val overviewPageScraper = DunckerOverviewPageScraper(clock)

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
                logger.info { "Scraped ${events.size} event(s) from Duncker Club" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val DUNCKER_LIMITATIONS =
    VenueLimitations(
        EventSource.DUNCKER,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one hand-coded page")
    )
