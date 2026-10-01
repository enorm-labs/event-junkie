package de.norm.events.scraper.crackbellmer

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Crack Bellmer, the RAW-Gelände microclub and dance bar, on Webflow.
 *
 * Listing → event page, but not the merge [de.norm.events.scraper.AbstractTwoPageWebsiteImporter]
 * performs:
 * 1. [HtmlFetcher] fetches `/program/this-month` conditionally (ETag / Last-Modified). All three
 * month tabs serve the same markup — the whole programme in one Finsweet CMS list, filtered
 * client-side — so one fetch gets everything.
 * 2. [CrackBellmerOverviewPageScraper] parses every `.event-item`, supplying every stored field
 * and dropping the passed nights the listing carries.
 * 3. Each remaining event's `/events/<slug>` page adds its blurb ([CrackBellmerDetailPageScraper]).
 *
 * Step 3 is why this class implements [EventImporter] directly: the event page adds a
 * description and nothing else, and spells its date without a year, so it is not the primary
 * source the base class's detail scraper must be. A failed event page is not fatal — the night
 * keeps its listing data, losing only the blurb.
 *
 * @see CrackBellmerOverviewPageScraper for the listing parsing logic.
 * @see CrackBellmerDetailPageScraper for the event-page blurb.
 * @see <a href="https://www.crackbellmer.de/program/this-month">Crack Bellmer programme</a>
 */
@Component
class CrackBellmerWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the listing scraper's past-event cutoff; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Crack Bellmer listing", CrackBellmerOverviewPageScraper(clock)::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.CRACK_BELLMER
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

    private val detailPageScraper = CrackBellmerDetailPageScraper()

    override suspend fun postProcess(events: List<ScrapedEvent>): List<ScrapedEvent> = events.map { addDescription(it) }

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
