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
 * One hand-coded page, no detail pages, so a single fetch: [HtmlFetcher] fetches `start.html`
 * conditionally (ETag / Last-Modified), [DunckerOverviewPageScraper] parses it.
 *
 * @see DunckerOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.dunckerclub.de/start.html">Duncker Club programme</a>
 */
@Component
class DunckerWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's past-event cutoff and year inference; override in tests. */
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
