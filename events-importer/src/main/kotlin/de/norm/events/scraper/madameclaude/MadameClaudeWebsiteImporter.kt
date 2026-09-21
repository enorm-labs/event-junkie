package de.norm.events.scraper.madameclaude

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Website importer for Madame Claude Berlin.
 *
 * WordPress with an Advanced Custom Fields `event` post type whose public REST API
 * (`/wp-json/wp/v2/event`) exposes every event as clean JSON — far more stable than the
 * previous two-page HTML scrape (grid plus a detail fetch per event); structured data is
 * priority 1 in ADR-007 §"Selector Strategy", and it needs one request:
 * 1. Build the query from the configured API base ([buildRequestUrl]) — upcoming `event`s only
 * (`after=<today>`), ordered by date, featured image embedded.
 * 2. Fetch the JSON body via [ApiClient.fetchJson] (shared politeness throttle and User-Agent).
 * 3. Parse it into [de.norm.events.scraper.ScrapedEvent]s via [MadameClaudeApiScraper].
 *
 * The list endpoint returns the full history (past included), so `after` restricts it to
 * upcoming events server-side (ADR-007 first-page-only: ~two dozen upcoming shows fit one
 * page). No ETag / Last-Modified conditional request — `etag` / `lastModified` are ignored and
 * every import returns [ImportResult.Success]; re-imports stay cheap and safe because
 * persistence upserts idempotently by `sourceId`.
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

        // No conditional-cache support here; ETag / Last-Modified are always null and change
        // detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * The WP REST `event` query from the configured API base [baseUrl]. Ordering, page size,
     * upcoming-only cut-off and image-embed flag are parsing concerns and live in code (ADR-007:
     * parsing logic in code, entry-point URL in config). The base is on the event source, e.g.
     * `https://madameclaude.de/wp-json/wp/v2/event`. `after` is start of today in Berlin so an
     * event later today is still included.
     */
    private fun buildRequestUrl(baseUrl: String): String {
        val separator = if ('?' in baseUrl) '&' else '?'
        val after = LocalDate.now(clock).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return "$baseUrl${separator}per_page=$PER_PAGE&orderby=date&order=asc&after=$after&_embed=wp:featuredmedia"
    }

    private companion object {
        /** Upper bound on events fetched in the single request; comfortably above ~two dozen upcoming shows. */
        const val PER_PAGE = 100
    }
}

/** Nothing this source withholds needs declaring (#715). */
val MADAME_CLAUDE_LIMITATIONS = VenueLimitations(EventSource.MADAME_CLAUDE)
