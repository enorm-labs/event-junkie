package de.norm.events.scraper.arcanoa

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
 * Website importer for Arcanoa Berlin's 1990s `veranst.htm` programme page.
 *
 * The venue's whole programme — currently three months — lives on that one hand-coded page
 * with no per-event detail pages, so the pipeline is a single fetch:
 * 1. Fetch `veranst.htm` via [HtmlFetcher] with conditional-request support. The host is one
 *    of the few that still serves a strong `ETag` *and* a `Last-Modified`, and the page only
 *    changes when the programme is edited, so 304s are both reliable and frequent here.
 * 2. Parse every dated programme line via [ArcanoaOverviewPageScraper].
 *
 * The configured source URL must point at `veranst.htm`; the site's `index.htm` is a frameset
 * landing page carrying no event data.
 *
 * @see ArcanoaOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.ssi-media.com/arcanoa/veranst.htm">Arcanoa programme</a>
 */
@Component
class ArcanoaWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's weekday-based year inference. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ARCANOA

    private val overviewPageScraper = ArcanoaOverviewPageScraper(clock)

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
                logger.info { "Scraped ${events.size} event(s) from Arcanoa" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val ARCANOA_LIMITATIONS =
    VenueLimitations(
        EventSource.ARCANOA,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one hand-coded page")
    )
