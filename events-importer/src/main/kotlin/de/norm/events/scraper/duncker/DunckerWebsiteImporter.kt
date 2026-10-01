package de.norm.events.scraper.duncker

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
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
    htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's past-event cutoff and year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Duncker Club", DunckerOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.DUNCKER
    override val listsWholeProgramme: Boolean = true
}

val DUNCKER_LIMITATIONS =
    VenueLimitations(
        EventSource.DUNCKER,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one hand-coded page"),
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "the time cell is the night's opening hours, stored as start and end, and no doors time is printed"
        ),
        AcceptedLimitation(LimitedAspect.PRICE, "the listing gives a night, a genre string and an hour range, never a figure")
    )
