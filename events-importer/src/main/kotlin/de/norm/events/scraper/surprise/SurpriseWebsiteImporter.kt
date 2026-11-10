package de.norm.events.scraper.surprise

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Surprise Club, a hand-built PHP site. `events-programs.php` lists the
 * coming weekend's nights, usually two, on one page with no API behind it ([SurpriseOverviewPageScraper]).
 *
 * The club holds one night a date and the site has no page per night, so the date is the night's identity.
 * The page names no start time; the home page's "Open: 10 p.m." belongs to one night and is not read.
 *
 * @see <a href="https://surprise-berlin.de/events-programs.php">Surprise Club events</a>
 */
@Component
class SurpriseWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Surprise Club events page", SurpriseOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SURPRISE
}

val SURPRISE_LIMITATIONS =
    VenueLimitations(
        EventSource.SURPRISE,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has one title line", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a party", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the events page prints no time", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.START_TIME, "the events page prints no time", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end of a night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the club sells entry at the door and links no ticket shop", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the club sells entry at the door only", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear", verified = "2026-10-17"),
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the site publishes no page per night, so every night points at the events page",
            verified = "2026-10-17"
        )
    )
