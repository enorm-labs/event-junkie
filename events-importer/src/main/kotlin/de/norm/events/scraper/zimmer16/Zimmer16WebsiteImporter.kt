package de.norm.events.scraper.zimmer16

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for ZIMMER 16: the homepage's YesTicket cards, then each card's YesTicket event page.
 *
 * The homepage holds the date and the title; the event page adds the time, the price and the text. The
 * YesTicket organiser list has more nights, but it also lists the children's theatre, which is out of scope.
 * The picture comes from the card, which serves it from the venue's own host; YesTicket's copy is robots-disallowed.
 *
 * @see Zimmer16OverviewPageScraper for the cards.
 * @see Zimmer16EventPageScraper for the event pages.
 */
@Component
class Zimmer16WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "ZIMMER 16", Zimmer16OverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ZIMMER_16
    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = Zimmer16EventPageScraper()::enrich
    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.START_TIME, ScrapedField.PRICES)
}

val ZIMMER_16_LIMITATIONS =
    VenueLimitations(
        EventSource.ZIMMER_16,
        AcceptedLimitation(LimitedAspect.PAGINATION, "the homepage shows the next 20 adult events, about four weeks"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue names no category, so a night without a format word is a concert, which mislabels a talk or a film night"
        ),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site has no line-up field; a title with a tagline or a programme name names nobody"),
        AcceptedLimitation(LimitedAspect.GENRE, "nothing on the cards or the event pages names a style"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each event states its start and end, no doors time"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "tickets are reserved on YesTicket and paid at the door")
    )
