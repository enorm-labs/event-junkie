package de.norm.events.scraper.festsaal

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
 * Website importer for Festsaal Kreuzberg Berlin.
 *
 * The public site (`festsaal-kreuzberg.de`) is a Nuxt.js SPA that renders no event
 * data server-side, but it is backed by a Wagtail headless CMS whose public JSON
 * REST API exposes every upcoming event as clean structured data. Importing from
 * that API is both possible without a headless browser and far more stable than any
 * HTML scrape (ADR-007 §"Selector Strategy" — structured data is priority 1), so
 * this importer:
 * 1. Builds the CMS query URL from the configured API base ([buildRequestUrl]) —
 *    all upcoming `EventPage`s ordered by date, in one request (ADR-007 first-page-only).
 * 2. Fetches the JSON body via [ApiClient.fetchJson] (shared politeness throttle
 *    and identifying User-Agent).
 * 3. Parses it into [de.norm.events.scraper.ScrapedEvent]s via [FestsaalApiScraper].
 *
 * The Wagtail API sends no ETag / Last-Modified, so conditional requests do not apply:
 * the `etag` / `lastModified` parameters are ignored and every import returns
 * [ImportResult.Success] (never [ImportResult.NotModified]). Re-imports stay cheap and
 * safe because persistence upserts idempotently by `sourceId`.
 *
 * @see FestsaalApiScraper for the JSON parsing logic.
 * @see <a href="https://festsaal-kreuzberg.de/de/programm/">Festsaal Kreuzberg programme</a>
 */
@Component
class FestsaalWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.FESTSAAL

    private val apiScraper = FestsaalApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val requestUrl = buildRequestUrl(url)
        val json = apiClient.fetchJson(requestUrl)
        val events = apiScraper.scrape(json)
        logger.info { "Scraped ${events.size} event(s) from Festsaal Kreuzberg" }

        // The Wagtail API has no conditional-request support, so there is no NotModified path;
        // ETag / Last-Modified are always null and change detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Builds the Wagtail EventPage query from the configured API base [baseUrl].
     *
     * The field set, page-type filter, locale, ordering, and page size are parsing
     * concerns and live in code (ADR-007: parsing logic in code, entry-point URL in
     * config). The base is stored on the event source, e.g.
     * `https://admin.festsaal-kreuzberg.de/api/v2/pages/`.
     */
    private fun buildRequestUrl(baseUrl: String): String {
        val separator = if ('?' in baseUrl) '&' else '?'
        return "$baseUrl${separator}type=home.EventPage&fields=$FIELDS&locale=de&order=date&limit=$LIMIT"
    }

    private companion object {
        /** Wagtail API fields the scraper reads; `genre(title)` / nested `preview_image` are expanded inline by the API. */
        const val FIELDS =
            "title,sub_title,date,doors,start,changed_date,changed_doors,changed_start,status,ticket,price,genre(title),preview_image,support"

        /** Upper bound on events fetched in the single request; comfortably above the venue's ~80 upcoming shows. */
        const val LIMIT = 100
    }
}

val FESTSAAL_LIMITATIONS =
    VenueLimitations(
        EventSource.FESTSAAL,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the API exposes no category field; its `genre` node is a musical genre, not an event kind")
    )
