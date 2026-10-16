package de.norm.events.scraper.bkatheater

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
 * Website importer for the BKA Theater (Berliner Kabarett Anstalt) at Mehringdamm, a hand-built PHP
 * site. `/spielplan/` lists about 300 performances a year ahead on one page of about 1.2 MB. Each
 * production page (`/prg/<pid>`) is then read once, for its text and the doors rule, and applied to
 * every performance of the production; a failed page flags its performances, so the upsert keeps
 * the text stored last time.
 *
 * `robots.txt` sets `Crawl-delay: 7`, so the run of about 100 production pages takes about twelve
 * minutes. The `/cal.php` calendar export it disallows is not read.
 *
 * @see BkaTheaterSpielplanPageScraper for the listing.
 * @see BkaTheaterProductionPageScraper for the production page.
 * @see <a href="https://www.bka-theater.de/spielplan/">BKA Theater Spielplan</a>
 */
@Component
class BkaTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "BKA Theater", BkaTheaterSpielplanPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.BKA_THEATER

    override val sharedPages: SharedPages<BkaTheaterProduction> = SharedPages(BkaTheaterProductionPageScraper()::scrape, ::withProduction)

    private fun withProduction(
        production: BkaTheaterProduction,
        event: ScrapedEvent
    ): ScrapedEvent =
        event.copy(
            description = production.description,
            doorsTime = production.doorsLead?.let { lead -> event.startTime?.minus(lead) }
        )
}

val BKA_THEATER_LIMITATIONS =
    VenueLimitations(
        EventSource.BKA_THEATER,
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the listing carries the shop price for its next 30 performances only"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the site states no box-office price"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a production billed without an act names its cast only in prose"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no presenter per performance"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site has one page per production, shared by all its performances")
    )
