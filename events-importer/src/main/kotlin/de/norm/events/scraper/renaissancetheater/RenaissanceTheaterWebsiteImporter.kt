package de.norm.events.scraper.renaissancetheater

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
 * Website importer for the Renaissance-Theater in Charlottenburg, a WordPress site with a theme of
 * its own and no events plugin or REST type. `/spielplan/` lists about 160 performances a year
 * ahead on one page of about 450 KB.
 *
 * Each production page (`/produktion/<slug>/`) is then read once, for its text and hero image, and
 * applied to every performance of the production, about 45 pages a run. A failed page flags its
 * performances, so the upsert keeps the text stored last time. `robots.txt` sets no crawl delay.
 *
 * @see RenaissanceTheaterSpielplanPageScraper for the listing.
 * @see RenaissanceTheaterProductionPageScraper for the production page.
 * @see <a href="https://renaissance-theater.de/spielplan/">Renaissance-Theater Spielplan</a>
 */
@Component
class RenaissanceTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Renaissance-Theater", RenaissanceTheaterSpielplanPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.RENAISSANCE_THEATER

    override val sharedPages: SharedPages<RenaissanceTheaterProduction> =
        SharedPages(RenaissanceTheaterProductionPageScraper()::scrape, ::withProduction)

    private fun withProduction(
        production: RenaissanceTheaterProduction,
        event: ScrapedEvent
    ): ScrapedEvent = event.copy(description = production.description, imageUrl = production.imageUrl ?: event.imageUrl)
}

val RENAISSANCE_THEATER_LIMITATIONS =
    VenueLimitations(
        EventSource.RENAISSANCE_THEATER,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site states one start time per performance"),
        AcceptedLimitation(LimitedAspect.IMAGE, "a production page shows a picture only for some productions"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the site states prices only in its ticket shop"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the site states prices only in its ticket shop"),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the listing names performers only in its billing prose, and a production lists its cast on its own page"
        ),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the site names a format only for some nights, so the type comes from words in the billing, and a night without one is typed a play"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names a presenter only in a production's prose"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site has one page per production, shared by all its performances")
    )
