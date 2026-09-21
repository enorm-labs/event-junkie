package de.norm.events.scraper.heimathafen

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Heimathafen Neukölln, sourced from its WordPress REST API.
 *
 * The Advanced Custom Fields `events` post type is exposed in full over `/wp-json/wp/v2/events`,
 * so nothing is scraped (ADR-007 §"Selector Strategy" priority 1): walk the paged listing via
 * [ApiClient.fetchJson], then [HeimathafenApiScraper] expands each post's
 * `acf.event_performances` into one event per dated performance, dropping past ones.
 *
 * **Every page is walked, not the first.** The endpoint returns the whole archive — 400+ posts,
 * 800+ performances — ordered by *post* date, while the event date is an ACF field WordPress
 * cannot filter or sort on. Upcoming performances are scattered across all pages (a capture
 * found 67 on page 1 and 29 more over pages 2–5), so stopping at the first would lose two
 * thirds of the programme. This is the per-importer pagination loop ADR-007 §"Pagination"
 * allows, bounded by [MAX_PAGES]; paging stops at the first short page, so nothing is
 * requested past the last one (WordPress answers 400 beyond it).
 *
 * No conditional request: `etag` / `lastModified` are ignored and every import returns
 * [ImportResult.Success], safe because persistence upserts idempotently by `sourceId`.
 *
 * The genre comes from the `events_tag-*` slugs `class_list` inlines, filtered to the ones the
 * genre vocabulary knows (#313) — no taxonomy request.
 *
 * @see HeimathafenApiScraper for the JSON parsing logic.
 */
@Component
class HeimathafenWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.HEIMATHAFEN

    private val apiScraper = HeimathafenApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val events = mutableListOf<ScrapedEvent>()
        for (page in 1..MAX_PAGES) {
            val parsed = apiScraper.scrape(apiClient.fetchJson(buildRequestUrl(url, page)))
            events += parsed.events
            // A short page is the last one: asking for the next would 400 (`rest_post_invalid_page_number`).
            if (parsed.postCount < PER_PAGE) break
            if (page == MAX_PAGES) logger.warn { "Heimathafen paging stopped at the $MAX_PAGES-page cap; later pages were not read" }
        }

        val distinct = events.distinctBy { it.sourceId }
        logger.info { "Scraped ${distinct.size} upcoming event(s) from Heimathafen" }
        return ImportResult.Success(events = distinct, etag = null, lastModified = null)
    }

    /**
     * The WP REST query for one [page] from the configured API base [baseUrl]. Page size and field
     * projection are parsing concerns and live in code (ADR-007: parsing logic in code, entry-point
     * URL in config). The base is on the event source, e.g.
     * `https://heimathafen-neukoelln.de/wp-json/wp/v2/events`.
     */
    private fun buildRequestUrl(
        baseUrl: String,
        page: Int
    ): String {
        val separator = if ('?' in baseUrl) '&' else '?'
        return "$baseUrl${separator}per_page=$PER_PAGE&page=$page&_fields=$FIELDS"
    }

    private companion object {
        /** WordPress's maximum page size, so the archive needs the fewest requests. */
        const val PER_PAGE = 100

        /**
         * Safety bound on the paging loop. The archive is ~5 pages today; the cap makes a runaway loop
         * impossible if the endpoint ever stops shortening its last page, and is logged when hit.
         */
        const val MAX_PAGES = 20

        /**
         * The fields the parser reads. `class_list` matters: it inlines the venue's `events_cat-*`
         * taxonomy slug, which types the event, sparing a second request to resolve category ids.
         */
        const val FIELDS = "id,link,title,excerpt,content,acf,class_list,featured_images"
    }
}

/** Nothing this source withholds needs declaring (#715). */
val HEIMATHAFEN_LIMITATIONS = VenueLimitations(EventSource.HEIMATHAFEN)
