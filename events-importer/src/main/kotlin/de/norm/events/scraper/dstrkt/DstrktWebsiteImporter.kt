package de.norm.events.scraper.dstrkt

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for DSTRKT, a Wix one-pager. The home page's "UPCOMING EVENTS" repeater is the
 * whole programme, a few nights at a time, and its records arrive in the page's warmup JSON
 * ([DstrktOverviewPageScraper]). One conditional fetch per run; the RA ticket links are stored, never fetched.
 *
 * @see <a href="https://www.dstrkt.de/">DSTRKT</a>
 */
@Component
class DstrktWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the DSTRKT home page", DstrktOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.DSTRKT
    override val listsWholeProgramme: Boolean = true
}

val DSTRKT_LIMITATIONS =
    VenueLimitations(
        EventSource.DSTRKT,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night is a title, a date, a start time and a ticket link", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the venue leaves the collection's description field empty", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every listed night is a club night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints at most a last day, never an end time", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the repeater carries no picture per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices are only on the ticket shop the site links", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site names the party, never its lineup", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the collection has no status field, so a cancelled night can only disappear", verified = "2026-10-17"),
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the site publishes no page per night, so every night points at the home page",
            verified = "2026-10-17"
        )
    )
