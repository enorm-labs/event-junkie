package de.norm.events.scraper.pfefferbergtheater

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Pfefferberg Theater in the Schankhalle Pfefferberg, a ProcessWire site. Not
 * Pfefferberg Haus 13, a different hall on the same grounds. `/spielplan/kalender/` lists about 110
 * performances a year ahead on one page; each production page is then read once for its text, image
 * and ticket link, and applied to every date of the production.
 *
 * A performance is keyed by its `eventdate_id`. Whether the house keeps that id when it moves a date is
 * unproven; if not, a moved date stales one row and adds one. The children's programme (category
 * `Kindertheater`) is marked `for-children`. The separate `Märchen im Glaspalast` calendar is not read.
 *
 * @see PfefferbergTheaterCalendarPageScraper for the listing.
 * @see PfefferbergTheaterProductionPageScraper for the production page.
 * @see <a href="https://pfefferberg-theater.de/spielplan/kalender/">Pfefferberg Theater Spielplan</a>
 */
@Component
class PfefferbergTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Pfefferberg Theater", PfefferbergTheaterCalendarPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.PFEFFERBERG_THEATER

    override val sharedPages: SharedPages<PfefferbergTheaterProduction> =
        SharedPages(PfefferbergTheaterProductionPageScraper()::scrape, ::withProduction)

    private fun withProduction(
        production: PfefferbergTheaterProduction,
        event: ScrapedEvent
    ): ScrapedEvent =
        event.copy(
            description = production.description,
            imageUrl = production.imageUrl,
            ticketUrl = production.ticketUrl
        )
}

val PFEFFERBERG_THEATER_LIMITATIONS =
    VenueLimitations(
        EventSource.PFEFFERBERG_THEATER,
        AcceptedLimitation(LimitedAspect.PRICE, "the site states no price; the ticket shop holds it"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes only the start time"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a production names its cast only in prose; the ensemble line is a company or a series"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no presenter per performance")
    )
