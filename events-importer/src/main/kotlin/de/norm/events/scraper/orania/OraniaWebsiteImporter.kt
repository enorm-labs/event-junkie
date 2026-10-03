package de.norm.events.scraper.orania

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.nextPageUrl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the concerts in the bar of the Orania.Berlin hotel, a TYPO3 site with the
 * Calendarize extension.
 *
 * `/concerts` lists ten concerts a page, about two weeks; `/concerts/page/N` runs about three
 * pages into the next season, and every page is read. Each event page adds the biography and the
 * full-size photo. The event pages carry a schema.org `Event` only under a `WebPage`'s
 * `mainEntity`, with a midnight `endDate` the venue never states, so the HTML is read instead.
 *
 * Entry is free to every concert, so no event has a price or a ticket link.
 *
 * @see <a href="https://orania.berlin/concerts">Orania.Concerts</a>
 */
@Component
class OraniaWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, OraniaOverviewPageScraper()::scrape, OraniaDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ORANIA

    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? = document.nextPageUrl(NEXT_PAGE_SELECTOR)

    private companion object {
        const val NEXT_PAGE_SELECTOR = "ul.pagination li.next a[href]"
    }
}

val ORANIA_LIMITATIONS =
    VenueLimitations(
        EventSource.ORANIA,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue states one time per concert, which is taken as the start"),
        AcceptedLimitation(LimitedAspect.END_TIME, "every concert is billed open end"),
        AcceptedLimitation(LimitedAspect.PRICE, "entry is free to every concert"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entry is free and the venue sells no tickets"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue tags a series such as piano or grooves, never a genre; every concert takes the house's jazz"),
        houseGenre = "Jazz"
    )
