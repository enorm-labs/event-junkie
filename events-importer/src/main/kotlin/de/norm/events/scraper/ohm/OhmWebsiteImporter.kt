package de.norm.events.scraper.ohm

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
 * Website importer for OHM Berlin's home-page programme.
 *
 * The whole programme is one page with no detail pages, so a single fetch: [HtmlFetcher]
 * fetches the home page conditionally (ETag / Last-Modified), [OhmOverviewPageScraper] parses
 * every `li.event-item`. The `/archives` page in the same section is not crawled — it holds
 * only past events.
 *
 * @see OhmOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://ohmberlin.com/">OHM Berlin</a>
 */
@Component
class OhmWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.OHM

    private val overviewPageScraper = OhmOverviewPageScraper(clock)

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
                logger.info { "Scraped ${events.size} event(s) from OHM" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val OHM_LIMITATIONS =
    VenueLimitations(
        EventSource.OHM,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the venue's whole programme is one page"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme page carries no per-event image"),
        AcceptedLimitation(LimitedAspect.PRICE, "the programme page carries no price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the programme page links no ticket shop"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue publishes no categories; every night is a DJ programme")
    )
