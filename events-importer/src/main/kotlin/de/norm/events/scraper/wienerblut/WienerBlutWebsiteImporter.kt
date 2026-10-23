package de.norm.events.scraper.wienerblut

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Wiener Blut's Wix Events list, one request per run: `/event-list` carries every listed gig in its
 * `wix-warmup-data` payload, with `hasMore: false`. The `/event-details/<slug>` page adds no ticket tier and only a
 * Linktree preview as its text, so it is not read.
 */
@Component
class WienerBlutWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Wiener Blut", WienerBlutOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.WIENER_BLUT
    override val listsWholeProgramme: Boolean = true
}

val WIENER_BLUT_LIMITATIONS =
    VenueLimitations(
        EventSource.WIENER_BLUT,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the list has no category, so a night is a concert unless its title says otherwise"),
        AcceptedLimitation(LimitedAspect.PRICE, "the Wix registration sells no ticket and the site prints no price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the site sells no tickets and links no shop"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the list gives one start time per night"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the list names no promoter")
    )
