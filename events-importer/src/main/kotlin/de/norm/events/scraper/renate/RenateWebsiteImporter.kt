package de.norm.events.scraper.renate

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Renate's homepage programme.
 *
 * WordPress with no `event` post type in its REST API (only stock types plus `blog_post`) and
 * no schema.org data, so the homepage — the whole programme inline, per-floor lineups included
 * — is the source: [HtmlFetcher] fetches it conditionally, [RenateOverviewPageScraper] parses
 * every `.prog-row`.
 *
 * @see RenateOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.renate.cc/">Renate Berlin</a>
 */
@Component
class RenateWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Renate", RenateOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.RENATE
    override val listsWholeProgramme: Boolean = true
}

val RENATE_LIMITATIONS =
    VenueLimitations(
        EventSource.RENATE,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club states no category; its `.cat-btn` names the spaces in use, not a kind of event"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every night points at the programme page"),
        AcceptedLimitation(
            LimitedAspect.START_TIME,
            "the club prints a time for its GARDEN and GREEN rooms inside the floor heading and none for a CLUB-only night"
        ),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the club sells through Resident Advisor and prints no figure; a night is flagged free only when its blurb says so"
        ),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme rows carry no flyer, only icons and a Resident Advisor ticket link"),
        houseGenre = "Techno, House"
    )
