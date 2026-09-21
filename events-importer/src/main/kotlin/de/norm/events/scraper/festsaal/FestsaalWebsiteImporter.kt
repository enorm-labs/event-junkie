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
 * The public site (`festsaal-kreuzberg.de`) is a Nuxt.js SPA rendering no event data
 * server-side, but its Wagtail headless CMS exposes every upcoming event over a public JSON
 * REST API — possible without a headless browser and far more stable than any HTML scrape
 * (ADR-007 §"Selector Strategy" — structured data is priority 1):
 * 1. Build the CMS query from the configured API base ([buildRequestUrl]) — all upcoming
 * `EventPage`s ordered by date, one request (ADR-007 first-page-only).
 * 2. Fetch the JSON body via [ApiClient.fetchJson] (shared politeness throttle and User-Agent).
 * 3. Parse it into [de.norm.events.scraper.ScrapedEvent]s via [FestsaalApiScraper].
 *
 * The API sends no ETag / Last-Modified, so `etag` / `lastModified` are ignored and every
 * import returns [ImportResult.Success] (never [ImportResult.NotModified]). Re-imports stay
 * cheap and safe because persistence upserts idempotently by `sourceId`.
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

        // No conditional-request support, so no NotModified path; ETag / Last-Modified are always
        // null and change detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * The Wagtail EventPage query from the configured API base [baseUrl]. Field set, page-type
     * filter, locale, ordering and page size are parsing concerns and live in code (ADR-007:
     * parsing logic in code, entry-point URL in config). The base is on the event source, e.g.
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

        /** Upper bound on events fetched in the single request; comfortably above ~80 upcoming shows. */
        const val LIMIT = 100
    }
}

val FESTSAAL_LIMITATIONS =
    VenueLimitations(
        EventSource.FESTSAAL,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the API exposes no category field; its `genre` node is a musical genre, not an event kind")
    )
