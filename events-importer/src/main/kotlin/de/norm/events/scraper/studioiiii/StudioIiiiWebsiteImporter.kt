package de.norm.events.scraper.studioiiii

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Studio IIII, a Tilda site. The homepage server-renders the whole programme
 * as cover blocks, with no feed, no JSON and no page per night ([StudioIiiiOverviewPageScraper]).
 *
 * Two nights can share a date (an exhibition opening before a club night), so a night's identity
 * is its date and the slug of its first title line.
 *
 * @see <a href="https://studioiiii.de/">Studio IIII</a>
 */
@Component
class StudioIiiiWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Studio IIII homepage", StudioIiiiOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.STUDIO_IIII
    override val listsWholeProgramme: Boolean = true
}

val STUDIO_IIII_LIMITATIONS =
    VenueLimitations(
        EventSource.STUDIO_IIII,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a date line, a title and a line-up, no second title line"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category per night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the homepage links no ticket shop"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the night's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the homepage")
    )
