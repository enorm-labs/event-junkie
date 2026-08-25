package de.norm.events.scraper.neuezukunft

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Neue Zukunft Berlin.
 *
 * The public site (`neue-zukunft.org`) is a static landing page whose concert programme
 * is published only as an image-based monthly PDF poster and, on the page, an embedded
 * Elfsight "Event Calendar" widget rendered client-side. Neither is scrapeable as HTML,
 * but the widget's public boot API returns every event as clean structured JSON — the
 * most stable possible source (ADR-007 §"Selector Strategy" — structured data is
 * priority 1), reachable without a headless browser. This importer:
 * 1. Fetches the widget boot JSON via [ApiClient.fetchJson] (shared politeness
 *    throttle and identifying User-Agent). The configured `url` is the boot endpoint
 *    carrying the widget id (`core.service.elfsight.com/p/boot/?w=<widgetId>`) and is
 *    used verbatim — all events come back in the single response (ADR-007 first-page-only).
 * 2. Parses it into [de.norm.events.scraper.ScrapedEvent]s via [NeueZukunftApiScraper].
 *
 * The Elfsight boot API sends no ETag / Last-Modified, so conditional requests do not
 * apply: the `etag` / `lastModified` parameters are ignored and every import returns
 * [ImportResult.Success] (never [ImportResult.NotModified]). Re-imports stay cheap and
 * safe because persistence upserts idempotently by `sourceId`.
 *
 * @see NeueZukunftApiScraper for the JSON parsing logic.
 * @see <a href="https://neue-zukunft.org/">Neue Zukunft</a>
 */
@Component
class NeueZukunftWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.NEUE_ZUKUNFT

    private val apiScraper = NeueZukunftApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val json = apiClient.fetchJson(url)
        val events = apiScraper.scrape(json)
        logger.info { "Scraped ${events.size} event(s) from Neue Zukunft" }

        // The Elfsight boot API has no conditional-request support, so there is no NotModified path;
        // ETag / Last-Modified are always null and change detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }
}

val NEUE_ZUKUNFT_LIMITATIONS =
    VenueLimitations(
        EventSource.NEUE_ZUKUNFT,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the calendar widget exposes no per-event URLs")
    )
