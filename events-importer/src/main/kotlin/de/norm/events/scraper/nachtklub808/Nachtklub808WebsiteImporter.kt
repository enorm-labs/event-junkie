package de.norm.events.scraper.nachtklub808

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for 808 Nachtklub Berlin: the one-pager alone, whose `EventList` island holds every night.
 *
 * @see Nachtklub808OverviewPageScraper for the island props and the opening hours.
 */
@Component
class Nachtklub808WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "808 Nachtklub", Nachtklub808OverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.NACHTKLUB_808
    override val listsWholeProgramme: Boolean = true
}

val NACHTKLUB_808_LIMITATIONS =
    VenueLimitations(
        EventSource.NACHTKLUB_808,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the club names one opening hour per weekday, which is read as the start"),
        AcceptedLimitation(LimitedAspect.PRICE, "the page names no entry price per night"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme is a text list with no posters"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "each night is a name and a DJ list, with no text"),
        AcceptedLimitation(LimitedAspect.GENRE, "no night names a style"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "a night has no page of its own")
    )
