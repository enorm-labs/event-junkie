package de.norm.events.scraper.studioiiii

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
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a date line, a title and a line-up, no second title line", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the homepage links no ticket shop", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the night's name", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the homepage", verified = "2026-10-17")
    )
