package de.norm.events.scraper.roadrunner

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Roadrunner's Paradise' retro `programm.html` page.
 *
 * The whole programme is one hand-coded page with no detail pages, so a single fetch:
 * [HtmlFetcher] fetches `programm.html` conditionally (ETag / Last-Modified),
 * [RoadrunnerOverviewPageScraper] parses it. The configured source URL must point at
 * `programm.html` (the homepage is a separate landing page with no event data).
 *
 * @see RoadrunnerOverviewPageScraper for the HTML parsing logic.
 * @see <a href="http://www.roadrunners-paradise.de/programm.html">Roadrunner's Paradise programme</a>
 */
@Component
class RoadrunnerWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's past-event cutoff and year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Roadrunner's Paradise", RoadrunnerOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.ROADRUNNER
    override val listsWholeProgramme: Boolean = true
}

val ROADRUNNER_LIMITATIONS =
    VenueLimitations(
        EventSource.ROADRUNNER,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme lives on one hand-coded page"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the retro programme carries no category field; a live-music venue, so an unmarked title defaults to a concert"
        ),
        houseGenre = "Rock"
    )
