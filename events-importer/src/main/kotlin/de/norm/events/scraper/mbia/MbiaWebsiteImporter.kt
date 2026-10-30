package de.norm.events.scraper.mbia

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for M-BIA, a Wix site. The `/veranstaltungen` page carries the whole collection
 * of nights in its warmup JSON ([MbiaOverviewPageScraper]), so one conditional fetch per run reads
 * the programme. The ticket links are stored, never fetched.
 *
 * @see <a href="https://www.m-bia.de/veranstaltungen">M-BIA</a>
 */
@Component
class MbiaWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the M-BIA events page", MbiaOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.M_BIA
    override val listsWholeProgramme: Boolean = true
}

val M_BIA_LIMITATIONS =
    VenueLimitations(
        EventSource.M_BIA,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "a night carries only its name"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a club night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "most nights state no time"),
        AcceptedLimitation(LimitedAspect.START_TIME, "a start time appears only in prose; every record carries the same unshown 22:00"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price; tickets link to RA, Eventbrite or ticket.io"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the night's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the collection has no status field, so a cancelled night can only disappear")
    )
