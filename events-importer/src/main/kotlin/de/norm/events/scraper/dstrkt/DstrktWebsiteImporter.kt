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
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night is a title, a date, a start time and a ticket link"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the venue leaves the collection's description field empty"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every listed night is a club night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints at most a last day, never an end time"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the repeater carries no picture per night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices are only on the ticket shop the site links"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site names the party, never its lineup"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the collection has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the home page")
    )
