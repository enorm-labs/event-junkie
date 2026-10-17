package de.norm.events.scraper.kubiz

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.walkWpRestPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for KuBiZ, from the WordPress posts the house announces each night with.
 *
 * The REST API answers only as `/?rest_route=/wp/v2/posts`: the site has no pretty permalinks, so
 * `/wp-json/` redirects to the home page. The archive goes back to 2005, so the query asks only
 * for posts published in the last [WINDOW_DAYS] days (`after=`), newest first; a night is
 * announced days or weeks ahead. [KubizApiScraper] reads the date from each title and drops the
 * news posts, the participation formats and the past nights. No conditional request: every
 * import returns [ImportResult.Success], and the upsert is idempotent by `sourceId`.
 */
@Component
class KubizWebsiteImporter(
    private val apiClient: ApiClient,
    /** Clock for the publish window and the past-night cut-off; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.KUBIZ

    private val apiScraper = KubizApiScraper(clock)

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val after = LocalDate.now(clock).minusDays(WINDOW_DAYS)
        val listing = apiClient.walkWpRestPages(eventSource, { buildRequestUrl(url, after, it) }, PER_PAGE, MAX_PAGES, apiScraper::scrape)
        val events = listing.items.distinctBy { it.sourceId }
        logger.info { "Scraped ${events.size} upcoming event(s) from KuBiZ" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = listing.complete)
    }

    private fun buildRequestUrl(
        baseUrl: String,
        after: LocalDate,
        page: Int
    ): String = "$baseUrl${baseUrl.querySeparator()}per_page=$PER_PAGE&page=$page&after=${after}T00:00:00&_fields=$FIELDS"

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 5
        const val WINDOW_DAYS = 180L
        const val FIELDS = "id,date,link,title,content"
    }
}

val KUBIZ_LIMITATIONS =
    VenueLimitations(
        EventSource.KUBIZ,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "a post names one time, the start"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "a post links the ticket shop's home page at most, never the night"),
        AcceptedLimitation(LimitedAspect.GENRE, "a post names no style beyond the title's format word"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the house marks no night as sold out")
    )
