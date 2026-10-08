package de.norm.events.scraper.showfenster

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Showfenster Theater, a Wix site whose calendar page carries the whole programme in its warmup JSON.
 *
 * The payload holds every event, past nights included, so one request per import. Each event's Wix page repeats the
 * calendar entry, and its Eventfrog page is another site, so neither is fetched.
 *
 * @see ShowfensterOverviewPageScraper for the parsing.
 */
@Component
class ShowfensterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Showfenster Theater", ShowfensterOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SHOWFENSTER
    override val listsWholeProgramme: Boolean = true
}

val SHOWFENSTER_LIMITATIONS =
    VenueLimitations(
        EventSource.SHOWFENSTER,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the calendar gives each event a title and one short text"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices are on the Eventfrog ticket shop, not on the venue's site"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar gives a start and an end time, no doors time"),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "only a concert or comedy title names its act; a reading bills a duo by surnames, and shows and quizzes name none"
        )
    )
