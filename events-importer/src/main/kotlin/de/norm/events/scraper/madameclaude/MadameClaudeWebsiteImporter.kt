package de.norm.events.scraper.madameclaude

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Website importer for Madame Claude Berlin.
 *
 * Madame Claude runs on WordPress with an Advanced Custom Fields `event` post type, whose
 * public REST API (`/wp-json/wp/v2/event`) exposes every event as clean structured JSON.
 * Importing from that API is far more stable than the previous two-page HTML scrape (an
 * events grid plus a detail-page fetch per event) — structured data is priority 1 in
 * ADR-007 §"Selector Strategy" — and needs a single request, so this importer:
 * 1. Builds the query URL from the configured API base ([buildRequestUrl]) — upcoming
 *    `event`s only (`after=<today>`), ordered by date, with the featured image embedded.
 * 2. Fetches the JSON body via [ApiClient.fetchJson] (shared politeness throttle and
 *    identifying User-Agent).
 * 3. Parses it into [de.norm.events.scraper.ScrapedEvent]s via [MadameClaudeApiScraper].
 *
 * The WP REST list endpoint returns the venue's full event history (past included), so the
 * `after` filter restricts the response to upcoming events server-side (ADR-007
 * first-page-only: ~two dozen upcoming shows fit in one page). No ETag / Last-Modified
 * conditional request is used — the `etag` / `lastModified` parameters are ignored and every
 * import returns [ImportResult.Success]; re-imports stay cheap and safe because persistence
 * upserts idempotently by `sourceId`.
 *
 * @see MadameClaudeApiScraper for the JSON parsing logic.
 * @see <a href="https://madameclaude.de/events/">Madame Claude Events</a>
 */
@Component
class MadameClaudeWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MADAME_CLAUDE

    private val apiScraper = MadameClaudeApiScraper()

    /** Berlin-local clock so the `after` cut-off is start of the venue's own day, not UTC. */
    private val clock: Clock = Clock.system(BERLIN)

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val requestUrl = buildRequestUrl(url)
        val json = apiClient.fetchJson(requestUrl)
        val events = apiScraper.scrape(json)
        logger.info { "Scraped ${events.size} event(s) from Madame Claude" }

        // The WP REST endpoint's conditional-cache support is not used here; ETag / Last-Modified
        // are always null and change detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Builds the WP REST `event` query from the configured API base [baseUrl].
     *
     * The ordering, page size, upcoming-only cut-off and image-embed flag are parsing concerns
     * and live in code (ADR-007: parsing logic in code, entry-point URL in config). The base is
     * stored on the event source, e.g. `https://madameclaude.de/wp-json/wp/v2/event`. `after`
     * is start of today in Berlin so an event later today is still included.
     */
    private fun buildRequestUrl(baseUrl: String): String {
        val separator = if ('?' in baseUrl) '&' else '?'
        val after = LocalDate.now(clock).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return "$baseUrl${separator}per_page=$PER_PAGE&orderby=date&order=asc&after=$after&_embed=wp:featuredmedia"
    }

    private companion object {
        /** Time zone whose civil date bounds the `after` filter — Madame Claude is in Berlin. */
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        /** Upper bound on events fetched in the single request; comfortably above the venue's ~two dozen upcoming shows. */
        const val PER_PAGE = 100
    }
}

/** Nothing this source withholds needs declaring (#715). */
val MADAME_CLAUDE_LIMITATIONS = VenueLimitations(EventSource.MADAME_CLAUDE)
