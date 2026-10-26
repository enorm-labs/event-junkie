package de.norm.events.scraper.liquidrom

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Liquidrom, the saltwater bath under a concrete dome at the Tempodrom. Its `Sound & Culture` page
 * (`/de/events.php`) holds the whole programme, server-rendered: the Sound Session DJ nights and the DOME 36 specials. The
 * nights take place in the bath during its opening hours, so entry is a bath time slot, booked on `tickets.php`.
 *
 * @see LiquidromOverviewPageScraper for the parsing.
 * @see <a href="https://www.liquidrom-berlin.de/de/events.php">Liquidrom Sound & Culture</a>
 */
@Component
class LiquidromWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the year of a special's date and the past-event cutoff; override in tests. */
    clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Liquidrom", LiquidromOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.LIQUIDROM
    override val listsWholeProgramme: Boolean = true
}

val LIQUIDROM_LIMITATIONS =
    VenueLimitations(
        EventSource.LIQUIDROM,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the programme is one page, and a special is an anchor on it"),
        AcceptedLimitation(LimitedAspect.PRICE, "entry is a bath time slot, and the page prints no price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "every night links the bath's general time-slot booking"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the nights run inside the bath's opening hours"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "a Sound Session row has only its DJ and a genre line"),
        AcceptedLimitation(LimitedAspect.IMAGE, "a Sound Session row has no image"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a special names its show, not its performers"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the page marks no night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a cancelled night leaves the page instead of being marked")
    )
