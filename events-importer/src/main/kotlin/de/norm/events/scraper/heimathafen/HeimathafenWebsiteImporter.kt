package de.norm.events.scraper.heimathafen

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

/**
 * Website importer for Heimathafen Neukölln, sourced from its WordPress REST API.
 *
 * The venue runs WordPress with an Advanced Custom Fields `events` post type whose public REST
 * endpoint (`/wp-json/wp/v2/events`) exposes the whole programme as structured JSON — no HTML
 * scraping (ADR-007 §"Selector Strategy" priority 1). The pipeline is:
 * 1. Walk the paged listing via [ApiClient.fetchJson], newest post first, requesting only the
 *    fields the parser reads ([FIELDS]).
 * 2. Parse each page via [HeimathafenApiScraper], which expands every post's
 *    `acf.event_performances` array into one event per dated performance and drops past ones.
 *
 * **Why every page, rather than the first:** the endpoint returns the venue's whole archive — 400+
 * posts and 800+ performances — ordered by *post* date, and the event date lives in an ACF field
 * that WordPress cannot filter or sort on server-side. Upcoming performances are therefore
 * scattered across all pages (a recent capture found 67 on page 1 but 29 more spread over pages
 * 2–5), so stopping at the first page would silently lose two thirds of the programme. This is the
 * per-importer pagination loop ADR-007 §"Pagination" allows, bounded by [MAX_PAGES]; paging stops
 * as soon as a page comes back short, so no request is ever made past the last page (WordPress
 * answers 400 beyond it).
 *
 * No ETag / Last-Modified conditional request is used — the `etag` / `lastModified` parameters are
 * ignored and every import returns [ImportResult.Success]; re-imports stay cheap and safe because
 * persistence upserts idempotently by `sourceId`.
 *
 * **No genre is stored**, though the venue does tag its events: the `events_tag` vocabulary runs to
 * 560 terms mixing real genres (Soul, Cumbia) with formats and access notes (Konzert, Premiere,
 * Gebärdensprache), the payload carries only term *ids* (six more requests to resolve), and the
 * slugs `class_list` inlines are lossy (`rb` for R&B). Resolving and caching that taxonomy once per
 * import is what would unblock the field — tracked in issue #313.
 *
 * @see HeimathafenApiScraper for the JSON parsing logic.
 * @see <a href="https://heimathafen-neukoelln.de/programm/">Heimathafen Neukölln programme</a>
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
     * Builds the WP REST query for one [page] from the configured API base [baseUrl].
     *
     * Page size and the field projection are parsing concerns and live in code (ADR-007: parsing
     * logic in code, entry-point URL in config). The base is stored on the event source, e.g.
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
        /** WordPress's maximum page size, so the archive needs the fewest possible requests. */
        const val PER_PAGE = 100

        /**
         * Safety bound on the paging loop. The archive is ~5 pages today; the cap keeps a runaway
         * loop impossible if the endpoint ever stops shortening its last page, and is logged when hit.
         */
        const val MAX_PAGES = 20

        /**
         * The fields the parser reads. `class_list` matters: it inlines the venue's own
         * `events_cat-*` taxonomy slug, which is what types the event, sparing a second request to
         * resolve category ids to names.
         */
        const val FIELDS = "id,link,title,excerpt,content,acf,class_list,featured_images"
    }
}

val HEIMATHAFEN_LIMITATIONS =
    VenueLimitations(
        EventSource.HEIMATHAFEN,
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "its `events_tag` vocabulary mixes genres with formats across 560 terms, the payload carries only term ids, and the inlined slugs are lossy"
        )
    )
