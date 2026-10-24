package de.norm.events.scraper.pfefferbergtheater

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.forChildrenIf
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Pfefferberg Theater in the Schankhalle Pfefferberg, a ProcessWire site. Not
 * Pfefferberg Haus 13, a different hall on the same grounds. `/spielplan/kalender/` lists about 110
 * performances a year ahead on one page. The site menu's `Märchen im Glaspalast` link then leads to the
 * season page of the second room, about 90 more; following the link keeps the next season without a seed
 * change. Each production page is read once for its text, image and ticket link, and applied to every
 * date of the production.
 *
 * A performance is keyed by its `eventdate_id`. Whether the house keeps that id when it moves a date is
 * unproven; if not, a moved date stales one row and adds one. The children's programme is marked
 * `for-children`: the calendar's `Kindertheater` category, or a fairy tale's family or school audience line.
 *
 * @see PfefferbergTheaterCalendarPageScraper for the listing.
 * @see PfefferbergTheaterProductionPageScraper for the production page.
 * @see <a href="https://pfefferberg-theater.de/spielplan/kalender/">Pfefferberg Theater Spielplan</a>
 */
@Component
class PfefferbergTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Pfefferberg Theater", PfefferbergTheaterCalendarPageScraper()::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.PFEFFERBERG_THEATER

    override val maxListingPages: Int = 2

    /** From the calendar, the menu's `Märchen im Glaspalast` season page; from that page, nothing. */
    override fun nextListingPage(
        document: Document,
        url: String
    ): String? {
        if (GLASPALAST_PATH in url) return null
        val link = document.select("a[href*='$GLASPALAST_PATH']").firstOrNull()?.absUrl("href")
        if (link == null) logger.warn { "No Märchen im Glaspalast link on $url, importing the calendar only" }
        return link
    }

    override val sharedPages: SharedPages<PfefferbergTheaterProduction> =
        SharedPages(PfefferbergTheaterProductionPageScraper()::scrape, ::withProduction)

    private fun withProduction(
        production: PfefferbergTheaterProduction,
        event: ScrapedEvent
    ): ScrapedEvent =
        event
            .copy(
                description = production.description,
                imageUrl = production.imageUrl,
                ticketUrl = production.ticketUrl
            ).forChildrenIf(production.childrensAudience)
}

val PFEFFERBERG_THEATER_LIMITATIONS =
    VenueLimitations(
        EventSource.PFEFFERBERG_THEATER,
        AcceptedLimitation(LimitedAspect.PRICE, "the site states no price; the ticket shop holds it"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes only the start time"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a production names its cast only in prose; the ensemble line is a company or a series"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no presenter per performance")
    )
