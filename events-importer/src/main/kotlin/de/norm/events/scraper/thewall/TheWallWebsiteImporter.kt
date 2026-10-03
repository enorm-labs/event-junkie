package de.norm.events.scraper.thewall

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.scrapeListingPages
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.THE_WALL
    override val fetchesBeyondEntryPage: Boolean = true

    private val pageScraper = TheWallEventsPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val listing =
                    htmlFetcher.scrapeListingPages(
                        eventSource,
                        fetchResult.document,
                        url,
                        MAX_PAGES,
                        { document, _ -> nextPartial(document) },
                        pageScraper::scrape
                    )
                logger.info { "Scraped ${listing.events.size} event(s) from The Wall Comedy Club" }

                ImportResult.Success(
                    events = listing.events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified,
                    complete = listing.complete
                )
            }
        }

    /** The "Show more" button's partial, or null on the last page. */
    private fun nextPartial(document: Document): String? = document.selectFirst(NEXT_PARTIAL_SELECTOR)?.absUrl("hx-get")?.takeIf { it.isNotEmpty() }

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
