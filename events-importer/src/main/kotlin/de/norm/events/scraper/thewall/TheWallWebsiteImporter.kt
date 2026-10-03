package de.norm.events.scraper.thewall

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for The Wall Comedy Club in Friedrichshain, whose site is a Spotagig tenant.
 *
 * `/venues/thewallcomedy/events/` shows twelve shows. Its "Show more" button loads the next twelve
 * from an HTMX partial, `/venues/<uuid>/partials/events/?page=N`, named in its `hx-get`. Each
 * partial names the next in the same way, and the last names none, so the walk reads them all:
 * about four pages, three months. The pages carry no event detail beyond the JSON-LD.
 *
 * @see TheWallEventsPageScraper for the field mapping.
 * @see <a href="https://thewallcomedy.com/venues/thewallcomedy/events/">The Wall Comedy Club events</a>
 */
@Component
class TheWallWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "The Wall Comedy Club", TheWallEventsPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.THE_WALL
    override val maxListingPages: Int = MAX_PAGES

    /** The "Show more" button's partial, or null on the last page. */
    override fun nextListingPage(
        document: Document,
        url: String
    ): String? = document.selectFirst(NEXT_PARTIAL_SELECTOR)?.absUrl("hx-get")?.takeIf { it.isNotEmpty() }

    private companion object {
        /** A runaway guard on the page walk; the programme runs to four pages. */
        const val MAX_PAGES = 10

        const val NEXT_PARTIAL_SELECTOR = "[hx-get*=/partials/events/]"
    }
}

val THE_WALL_LIMITATIONS =
    VenueLimitations(
        EventSource.THE_WALL,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each producer states one time per show, doors for some and the start for others"),
        AcceptedLimitation(LimitedAspect.PRICE, "most shows are pay-what-you-want, and a fixed price appears only in the prose"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "reservations run through the club's own Spotagig pages, which the event page is"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the showcases name no comedians, and a headliner appears only in the title")
    )
