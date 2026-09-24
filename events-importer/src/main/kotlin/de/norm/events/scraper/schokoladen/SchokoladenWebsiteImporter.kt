package de.norm.events.scraper.schokoladen

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.nextPageUrl
import de.norm.events.scraper.scrapeListingPages
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SCHOKOLADEN

    private val overviewPageScraper = SchokoladenOverviewPageScraper()

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
                val events =
                    htmlFetcher.scrapeListingPages(
                        eventSource,
                        fetchResult.document,
                        url,
                        MAX_PAGES,
                        { document, _ -> document.nextPageUrl(NEXT_PAGE_SELECTOR) },
                        overviewPageScraper::scrape
                    )
                logger.info { "Scraped ${events.size} event(s) from Schokoladen" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    private companion object {
        /** A runaway guard on the page walk; the listing runs to eight pages. */
        const val MAX_PAGES = 15

        /** The paginator's "Nächste" link, a relative `?page=N`. */
        const val NEXT_PAGE_SELECTOR = "a.page-link[rel=next]"
    }
}

/** Nothing this source withholds needs declaring (#715). */
val SCHOKOLADEN_LIMITATIONS = VenueLimitations(EventSource.SCHOKOLADEN)
