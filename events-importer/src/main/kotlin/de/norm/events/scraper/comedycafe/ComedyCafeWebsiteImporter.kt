package de.norm.events.scraper.comedycafe

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.walkTecEvents
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Comedy Café Berlin in Neukölln, over the club's The Events Calendar REST
 * API, as Cosmic Comedy is read.
 *
 * The API returns the upcoming programme, about five weeks, fifty events a page, and the walk
 * follows its cursor. Conditional requests are unused: the window moves with today, and upserts
 * are idempotent by `sourceId`.
 *
 * @see ComedyCafeApiScraper for the field mapping.
 * @see <a href="https://www.comedycafeberlin.com/">Comedy Café Berlin</a>
 */
@Component
class ComedyCafeWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.COMEDY_CAFE

    private val apiScraper = ComedyCafeApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = apiClient.walkTecEvents(eventSource, url, MAX_PAGES, apiScraper::scrapePage)
        logger.info { "Scraped ${listing.items.size} event(s) from Comedy Café Berlin across ${listing.pages} page(s)" }
        return ImportResult.Success(events = listing.items, etag = null, lastModified = null, complete = listing.complete)
    }

    private companion object {
        /** A runaway guard on the cursor walk; the programme is two pages. */
        const val MAX_PAGES = 10
    }
}

val COMEDY_CAFE_LIMITATIONS =
    VenueLimitations(
        EventSource.COMEDY_CAFE,
        AcceptedLimitation(LimitedAspect.ARTISTS, "an improv night bills a team or a format, and its performers appear only in prose"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the API carries no ticket link; the shop is a widget on each event page"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the bar sells leftover tickets at a surcharge the site states once for every show"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the club organises every show itself")
    )
