package de.norm.events.scraper.ohm

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
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
    htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "OHM", OhmOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.OHM
    override val listsWholeProgramme: Boolean = true
}

val OHM_LIMITATIONS =
    VenueLimitations(
        EventSource.OHM,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the venue's whole programme is one page"),
        AcceptedLimitation(LimitedAspect.PRICE, "the programme page carries no price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the programme page links no ticket shop"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue publishes no categories; every night is a DJ programme"),
        houseGenre = "Techno"
    )
