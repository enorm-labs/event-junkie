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
 * The whole programme — currently three months — is one hand-coded page with no detail pages,
 * so a single fetch: [HtmlFetcher] fetches `veranst.htm` conditionally — the host is one of the
 * few still serving a strong `ETag` *and* a `Last-Modified`, and the page changes only when
 * the programme is edited, so 304s are reliable and frequent — and
 * [ArcanoaOverviewPageScraper] parses every dated line.
 *
 * The configured source URL must point at `veranst.htm`; `index.htm` is a frameset landing
 * page with no event data.
 *
 * @see ArcanoaOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.ssi-media.com/arcanoa/veranst.htm">Arcanoa programme</a>
 */
@Component
class ArcanoaWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's weekday-based year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
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
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one hand-coded page"),
        AcceptedLimitation(LimitedAspect.PRICE, "a night is one line — a date, the act and a genre string — and the page prints no figure anywhere"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entry is paid at the door, and the page's only links point at partner sites"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the page carries no image element at all"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the one line per night is the whole entry, with no blurb after it")
    )
