package de.norm.events.scraper.lark

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for LARK Berlin, sourced from its WordPress REST API.
 *
 * The venue exposes an Advanced Custom Fields `event` post type in full over
 * `/wp-json/wp/v2/event`, so nothing is scraped (ADR-007 §"Selector Strategy" priority 1): walk the
 * listing newest-first via [ApiClient.fetchJson], parse each page with [LarkApiScraper], then resolve
 * the upcoming events' posters in one batched `/wp-json/wp/v2/media?include=<ids>` request.
 *
 * **Paging usually stops after one request** because LARK overloads `post.date` with the *event*
 * date, making the endpoint's newest-first ordering chronological in event terms — so the upcoming
 * programme sits at the front of page 1 and paging stops as soon as a page's oldest event is past.
 * [HeimathafenWebsiteImporter][de.norm.events.scraper.heimathafen.HeimathafenWebsiteImporter] walks
 * its whole archive for want of that. [MAX_PAGES] still bounds the loop.
 *
 * **The poster is fetched separately** because the listing carries only a `featured_media` id, and
 * WordPress's `_embed` inlines every generated size for every post — 308 KB → 845 KB in a live
 * capture, against ~2 KB for one batched lookup. A failed media response costs posters, never events.
 *
 * No conditional request is used: `etag` / `lastModified` are ignored and every import returns
 * [ImportResult.Success], which is safe because persistence upserts idempotently by `sourceId`.
 *
 * @see LarkApiScraper for the JSON parsing logic.
 * @see <a href="https://larkberlin.com/events/">LARK events</a>
 */
@Component
class LarkWebsiteImporter(
    private val apiClient: ApiClient,
    /** Clock deciding which events are still upcoming. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.LARK

    private val apiScraper = LarkApiScraper(clock)

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val entries = mutableListOf<LarkEntry>()
        val today = LocalDate.now(clock)

        for (page in 1..MAX_PAGES) {
            val parsed = apiScraper.scrapePage(apiClient.fetchJson(buildListingUrl(url, page)))
            entries += parsed.entries
            // The listing is ordered by event date, so a page reaching the past holds no more
            // upcoming events — and a short page is the last one (WordPress 400s beyond it).
            val lastPage = parsed.postCount < PER_PAGE || parsed.oldestDate?.let { it < today } == true
            if (lastPage) break
            if (page == MAX_PAGES) logger.warn { "LARK paging stopped at the $MAX_PAGES-page cap; later pages were not read" }
        }

        val events = withPosters(entries.distinctBy { it.event.sourceId }, url)
        logger.info { "Scraped ${events.size} upcoming event(s) from LARK" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /** Resolves the entries' `featured_media` ids in one request and applies the URLs to the events. */
    private suspend fun withPosters(
        entries: List<LarkEntry>,
        baseUrl: String
    ): List<ScrapedEvent> {
        val mediaIds = entries.mapNotNull { it.featuredMediaId }.distinct()
        if (mediaIds.isEmpty()) return entries.map { it.event }

        val posters = apiScraper.parseMedia(apiClient.fetchJson(buildMediaUrl(baseUrl, mediaIds)))
        if (posters.isEmpty()) logger.warn { "LARK media lookup returned no posters for ${mediaIds.size} attachment(s)" }

        return entries.map { entry ->
            entry.event.copy(imageUrl = entry.featuredMediaId?.let { posters[it] })
        }
    }

    /**
     * Builds the WP REST query for one listing [page] from the configured API base [baseUrl].
     *
     * Page size and the field projection are parsing concerns and live in code (ADR-007: parsing
     * logic in code, entry-point URL in config). The base is stored on the event source, e.g.
     * `https://larkberlin.com/wp-json/wp/v2/event`.
     */
    private fun buildListingUrl(
        baseUrl: String,
        page: Int
    ): String = "$baseUrl${baseUrl.querySeparator()}per_page=$PER_PAGE&page=$page&_fields=$FIELDS"

    /** Builds the batched attachment lookup, sharing the listing's host and `/wp/v2` namespace. */
    private fun buildMediaUrl(
        baseUrl: String,
        mediaIds: List<Long>
    ): String {
        val root = baseUrl.substringBefore('?').trimEnd('/').substringBeforeLast('/')
        return "$root/media?include=${mediaIds.joinToString(",")}&per_page=$PER_PAGE&_fields=id,source_url"
    }

    private fun String.querySeparator(): Char = if ('?' in this) '&' else '?'

    private companion object {
        /** WordPress's maximum page size, so the programme needs the fewest possible requests. */
        const val PER_PAGE = 100

        /**
         * Safety bound on the paging loop. One page covers the whole upcoming programme today; the
         * cap keeps a runaway loop impossible if the ordering assumption ever breaks, and is logged
         * when hit.
         */
        const val MAX_PAGES = 10

        /**
         * The fields the parser reads. `date` matters most: LARK stores the *event* date there, so
         * it supplies both the date and the doors time.
         */
        const val FIELDS = "id,link,title,date,acf,featured_media"
    }
}

val LARK_LIMITATIONS =
    VenueLimitations(
        EventSource.LARK,
        AcceptedLimitation(LimitedAspect.START_TIME, "the venue renders its one time as Doors and publishes no separate start time")
    )
