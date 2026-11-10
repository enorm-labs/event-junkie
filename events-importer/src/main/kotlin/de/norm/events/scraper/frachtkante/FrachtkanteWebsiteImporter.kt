package de.norm.events.scraper.frachtkante

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Frachtkante, a server-rendered ProcessWire one-pager whose `Veranstaltungen` section holds the programme.
 *
 * One request per import. Each event is a title, a date, a time line and, for some, a ticket link, with no page of its own.
 * The site names no category, so the title types the event: an open air is a party, a market stays other.
 *
 * @see FrachtkanteOverviewPageScraper for the parsing.
 */
@Component
class FrachtkanteWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Frachtkante", FrachtkanteOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.FRACHTKANTE
    override val listsWholeProgramme: Boolean = true
}

val FRACHTKANTE_LIMITATIONS =
    VenueLimitations(
        EventSource.FRACHTKANTE,
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "every event is a block on the homepage and takes its identity from its date plus its title",
            verified = "2026-10-13"
        ),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the site names no category, so a title without a format word is stored as other",
            verified = "2026-10-13"
        ),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme shows no flyer", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one opening time per event", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.GENRE, "the programme names no style", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices are only on the organiser's ticket shop", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the programme lists no line-up", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the organising collective is named only in the title", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "an event carries at most a ticket line, no blurb", verified = "2026-10-13")
    )
