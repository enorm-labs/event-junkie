package de.norm.events.scraper.crackbellmer

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Crack Bellmer, the RAW-Gelände microclub and dance bar, on Webflow.
 *
 * The pipeline is listing → event page, but not the merge
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] performs:
 * 1. Fetch the `/program/this-month` listing via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified). All three month tabs serve the same markup — the whole programme in one
 *    Finsweet CMS list, filtered client-side — so one fetch gets everything.
 * 2. Parse every `.event-item` via [CrackBellmerOverviewPageScraper], which supplies every stored
 *    field and drops the already-passed nights the listing carries.
 * 3. Fetch each remaining event's `/events/<slug>` page and add its blurb
 *    ([CrackBellmerDetailPageScraper]).
 *
 * Step 3 is why this class implements [EventImporter] directly: the event page adds a description
 * and nothing else, and spells its date without a year, so it is not the primary source the abstract
 * base class's detail scraper is expected to be. An event page that cannot be fetched or parsed is
 * not fatal — that night keeps its listing data, losing only the blurb.
 *
 * @see CrackBellmerOverviewPageScraper for the listing parsing logic.
 * @see CrackBellmerDetailPageScraper for the event-page blurb.
 * @see <a href="https://www.crackbellmer.de/program/this-month">Crack Bellmer programme</a>
 */
@Component
class CrackBellmerWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the listing scraper's past-event cutoff. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.CRACK_BELLMER

    private val overviewPageScraper = CrackBellmerOverviewPageScraper(clock)
    private val detailPageScraper = CrackBellmerDetailPageScraper()

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
                logger.info { "Scraped ${events.size} upcoming event(s) from the Crack Bellmer listing" }

                ImportResult.Success(
                    events = events.map { addDescription(it) },
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /** Fetches one event page for its blurb, degrading to the listing data so a broken page costs only the description. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken event page must not fail the whole import
    private suspend fun addDescription(event: ScrapedEvent): ScrapedEvent =
        try {
            event.copy(description = detailPageScraper.scrapeDescription(htmlFetcher.fetchDocument(event.sourceUrl)))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch event page for '${event.title}' (${event.sourceUrl}), keeping listing data" }
            event
        }
}

val CRACK_BELLMER_LIMITATIONS =
    VenueLimitations(
        EventSource.CRACK_BELLMER,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue emits no category at all; the type is read from the title and then the genre line"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes no doors time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes no prices"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the venue links no ticket shop")
    )
