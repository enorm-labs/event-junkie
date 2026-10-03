package de.norm.events.scraper.schokoladen

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.nextPageUrl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Schokoladen Mitte's Laravel-based event listing.
 *
 * The listing (`/`) carries every event's details inline (times, descriptions, ticket links,
 * images), addressed only by page fragment (`#e20260711`), so there are no detail pages. It is
 * paged ten events at a time through a plain `?page=N` link, eight pages into the next year
 * (#1883), and the importer reads it to the last page, bounded by [MAX_PAGES]. The site sends
 * no ETag or Last-Modified, so each cycle fetches every page.
 *
 * @see SchokoladenOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.schokoladen-mitte.de/">Schokoladen Mitte</a>
 */
@Component
class SchokoladenWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Schokoladen", SchokoladenOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SCHOKOLADEN
    override val maxListingPages: Int = MAX_PAGES

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? = document.nextPageUrl(NEXT_PAGE_SELECTOR)

    private companion object {
        /** A runaway guard on the page walk; the listing runs to eight pages. */
        const val MAX_PAGES = 15

        /** The paginator's "Nächste" link, a relative `?page=N`. */
        const val NEXT_PAGE_SELECTOR = "a.page-link[rel=next]"
    }
}

val SCHOKOLADEN_LIMITATIONS =
    VenueLimitations(
        EventSource.SCHOKOLADEN,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints doors, show time and a ticket link, never a figure")
    )
