package de.norm.events.scraper.richten25

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Richten25: the `/events` page alone, whose embed lists every upcoming night.
 *
 * @see Richten25OverviewPageScraper for the embed in the page props.
 */
@Component
class Richten25WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Richten25", Richten25OverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.RICHTEN25
    override val listsWholeProgramme: Boolean = true
}

val RICHTEN25_LIMITATIONS =
    VenueLimitations(
        EventSource.RICHTEN25,
        AcceptedLimitation(LimitedAspect.START_TIME, "the programme names a date and a lineup, no time"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme names a date and a lineup, no time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the page names no entry price"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme is a text list with no posters"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "each night is one lineup line, with no text"),
        AcceptedLimitation(LimitedAspect.GENRE, "the space is for experimental music, and no night names a style"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "a night has no page of its own")
    )
