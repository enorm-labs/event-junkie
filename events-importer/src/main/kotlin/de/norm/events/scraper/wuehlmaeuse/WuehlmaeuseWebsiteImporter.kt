package de.norm.events.scraper.wuehlmaeuse

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.walkWpRestPages
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
        val pageUrl = { page: Int -> "$url${url.querySeparator()}per_page=$WUEHLMAEUSE_PER_PAGE&page=$page" }
        val walked = apiClient.walkWpRestPages(eventSource, pageUrl, WUEHLMAEUSE_PER_PAGE, MAX_PAGES, apiScraper::scrapePage)
        val events = apiScraper.toEvents(walked.items)
        logger.info { "Scraped ${events.size} performance(s) from ${walked.items.size} Wühlmäuse ticket(s) across ${walked.pages} page(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = walked.complete)
    }

    private companion object {
        /** A runaway guard on the page walk; the shop runs to eighteen pages. */
        const val MAX_PAGES = 40
    }
}

/**
 * German is the house language because the programme is German in practice (#2584). On `2026-10-04` the
 * production API listed 402 upcoming shows under 177 titles, and no title named another language. The
 * shop carries no show text, so the titles and the cabaret programme are the evidence.
 */
val WUEHLMAEUSE_LIMITATIONS =
    VenueLimitations(
        EventSource.WUEHLMAEUSE,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the ticket products carry no show text"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the shop files every show under its act and names no format; Kabarett, comedy and music share one stage"),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop names no genre"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the shop sells one price per seat category and states no box-office price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the shop names no promoter"),
        houseLanguage = SpokenLanguage.GERMAN
    )
