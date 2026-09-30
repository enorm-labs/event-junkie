package de.norm.events.scraper.cosmiccomedy

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ListingPage
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.walkListingPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Cosmic Comedy Berlin, the English-language stand-up club on Schönhauser
 * Allee — entirely JSON-sourced over the venue's **The Events Calendar** REST API.
 *
 * The plugin returns the whole upcoming programme (default window from today to two years out)
 * fifty events at a time, handing back its own `next_rest_url` cursor, which this walks rather
 * than building page URLs. [MAX_PAGES] bounds the walk in case the cursor ever fails to terminate.
 *
 * Conditional requests are unused: the window is relative to *today*, so the same URL
 * legitimately yields a different payload each day, and upserts are idempotent by `sourceId`.
 *
 * One venue and one art form, so nothing to filter — everything the API returns is a comedy
 * night on Schönhauser Allee.
 *
 * @see CosmicComedyApiScraper for the parsing logic and the field mapping.
 * @see <a href="https://comedyclubberlin.com/events/">Cosmic Comedy Berlin programme</a>
 */
@Component
class CosmicComedyWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.COSMIC_COMEDY

    private val apiScraper = CosmicComedyApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val firstUrl = "$url${url.querySeparator()}per_page=$PER_PAGE"
        val fetchPage: suspend (String) -> CosmicComedyPage = { apiScraper.scrapePage(apiClient.fetchJson(it)) }
        val listing =
            walkListingPages(eventSource, fetchPage(firstUrl), firstUrl, MAX_PAGES, fetchPage) { page, _ -> ListingPage(page.events, page.nextPageUrl) }
        logger.info { "Scraped ${listing.items.size} event(s) from Cosmic Comedy Berlin across ${listing.pages} page(s)" }

        return ImportResult.Success(events = listing.items, etag = null, lastModified = null, complete = listing.complete)
    }

    companion object {
        /** The plugin's maximum page size, so the programme needs the fewest requests. */
        private const val PER_PAGE = 50

        /**
         * Safety bound on the cursor walk. The upcoming programme is two pages today; the cap only
         * stops a runaway if the API ever returns a cursor that does not terminate.
         */
        const val MAX_PAGES = 20
    }
}

val COSMIC_COMEDY_LIMITATIONS =
    VenueLimitations(
        EventSource.COSMIC_COMEDY,
        AcceptedLimitation(LimitedAspect.PRICE, "`cost` and `cost_details` are empty on every event")
    )
