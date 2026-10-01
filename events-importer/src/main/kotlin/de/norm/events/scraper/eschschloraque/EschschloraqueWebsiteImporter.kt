package de.norm.events.scraper.eschschloraque

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Eschschloraque Rümschrümp's Drupal 7 home page.
 *
 * The whole upcoming programme renders on the home page as full nodes, so a single fetch:
 * [HtmlFetcher] fetches it conditionally (ETag / Last-Modified),
 * [EschschloraqueOverviewPageScraper] parses every `.node-veranstaltung`.
 *
 * The `/kalender/monat` calendar in the same navigation is deliberately not crawled: it lists
 * the *current* month — passed nights included — and its `?/YYYY-MM` variants are empty beyond
 * it, so it adds no upcoming event the home page lacks. `/archiv` holds only past events.
 *
 * @see EschschloraqueOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.eschschloraque.de/">Eschschloraque Rümschrümp</a>
 */
@Component
class EschschloraqueWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Eschschloraque", EschschloraqueOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ESCHSCHLORAQUE
    override val listsWholeProgramme: Boolean = true
}

val ESCHSCHLORAQUE_LIMITATIONS =
    VenueLimitations(
        EventSource.ESCHSCHLORAQUE,
        AcceptedLimitation(LimitedAspect.PRICE, "entry is settled at the door and the venue names no figure"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the venue runs no ticket shop"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue flags nothing sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the venue flags nothing cancelled, so every event stays scheduled"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the programme mixes DJ nights, live sets, bingo and theatre with no kind field anywhere"),
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "the date field carries one ab-HH-Uhr time; a doors time exists only where the prose labels a pair, which is read"
        )
    )
