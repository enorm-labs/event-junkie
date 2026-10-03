package de.norm.events.scraper.wuehlmaeuse

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ListingPage
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.pageNumber
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.walkListingPages
import de.norm.events.scraper.withQueryParameter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Die Wühlmäuse, the Kabarett theatre at Theodor-Heuss-Platz, over its
 * WooCommerce Store API.
 *
 * The shop is the programme: every performance up to two years ahead is on sale, as one product per
 * price category, about 1,700 products for 400 performances. The Store API pages them a hundred at
 * a time with no cursor, so the walk asks for the next page until one comes back short. The theatre
 * has no events plugin, and its `/veranstaltung/<slug>` pages repeat what the products say.
 *
 * @see WuehlmaeuseApiScraper for the product shape and the fold into one event per performance.
 * @see <a href="https://wuehlmaeuse.de/">Die Wühlmäuse</a>
 */
@Component
class WuehlmaeuseWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.WUEHLMAEUSE

    private val apiScraper = WuehlmaeuseApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val firstUrl = "$url${url.querySeparator()}per_page=100&page=1"
        val fetchPage: suspend (String) -> WuehlmaeuseShopPage = { apiScraper.scrapePage(apiClient.fetchJson(it)) }
        val walked =
            walkListingPages(eventSource, fetchPage(firstUrl), firstUrl, MAX_PAGES, fetchPage) { page, pageUrl ->
                ListingPage(page.tickets, if (page.full) pageUrl.withQueryParameter("page", pageUrl.pageNumber() + 1) else null)
            }
        val events = apiScraper.toEvents(walked.items)
        logger.info { "Scraped ${events.size} performance(s) from ${walked.items.size} Wühlmäuse ticket(s) across ${walked.pages} page(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = walked.complete)
    }

    private companion object {
        /** A runaway guard on the page walk; the shop runs to eighteen pages. */
        const val MAX_PAGES = 40
    }
}

val WUEHLMAEUSE_LIMITATIONS =
    VenueLimitations(
        EventSource.WUEHLMAEUSE,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the ticket products carry no show text"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the shop files every show under its act and names no format; Kabarett, comedy and music share one stage"),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop names no genre"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the shop sells one price per seat category and states no box-office price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the shop names no promoter")
    )
