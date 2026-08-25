package de.norm.events.scraper.insel

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
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * Website importer for Kulturhaus Insel Berlin.
 *
 * The venue's homepage is its programme, rendered by a Gatsby front end over a DatoCMS backend. The
 * served HTML is a React shell, but Gatsby publishes every GraphQL query's result as a static JSON
 * artefact — so the whole programme is available as structured data with no CSS selectors at all
 * (ADR-007 §"Selector Strategy" priority 1).
 *
 * **The events are in a shared *static* query, not in the page's own `page-data.json`.** Gatsby keys
 * a static query's artefact by a hash of the query text (`/page-data/sq/d/3497155224.json`), which
 * is neither guessable nor stable across a query edit — so the hash is discovered rather than
 * configured:
 * 1. Fetch the page's own `page-data.json`, whose `staticQueryHashes` array lists every static query
 *    the page depends on ([toPageDataUrl]).
 * 2. Fetch each candidate artefact in turn and hand it to [InselApiScraper], stopping at the first
 *    one that *is* the events query — the scraper returns `null` for any other, so a sibling query
 *    publishing the same collection projected down to bare dates is skipped rather than parsed into
 *    title-less events.
 *
 * That costs one extra request plus however many candidates precede the events one (two of six at
 * capture), which a daily import can afford; the alternative — pinning the hash in configuration —
 * would break silently on the venue's next content-model change.
 *
 * The **programme page** is what the event source stores, not an artefact URL: it is the venue's
 * real, user-facing entry point and doubles as the events' `sourceUrl`, and the
 * `/page-data/<path>/page-data.json` layout it maps onto is a fixed Gatsby convention rather than a
 * per-venue detail (ADR-007: entry-point URL in config, derivation in code).
 *
 * No ETag / Last-Modified conditional request is used — the `etag` / `lastModified` parameters are
 * ignored and every import returns [ImportResult.Success]. The artefacts are regenerated on every
 * site rebuild, so their validators track the build rather than the programme, and re-imports stay
 * cheap because persistence upserts idempotently by `sourceId`.
 *
 * @see InselApiScraper for the JSON parsing logic and the source's limitations.
 * @see <a href="https://www.inselberlin.de/">Kulturhaus Insel Berlin</a>
 */
@Component
class InselWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.INSEL

    private val apiScraper = InselApiScraper()

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val events = fetchEvents(url)
        logger.info { "Scraped ${events.size} event(s) from Kulturhaus Insel Berlin" }

        // Gatsby's artefacts are rebuilt whenever the site is, so their ETag / Last-Modified track
        // the build rather than the programme; change detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Walks the page's static-query candidates and returns the events from the first artefact that
     * is the programme query, or an empty list when none of them is.
     */
    @Suppress("ReturnCount") // The early exits for "no candidates" and "found it" are clearer than nesting.
    private suspend fun fetchEvents(url: String): List<ScrapedEvent> {
        val hashes = staticQueryHashes(apiClient.fetchJson(toPageDataUrl(url)))
        if (hashes.isEmpty()) {
            logger.warn { "Insel page-data lists no staticQueryHashes; no programme artefact to read" }
            return emptyList()
        }

        for (hash in hashes) {
            val events = apiScraper.scrape(apiClient.fetchJson(toStaticQueryUrl(url, hash)), url)
            if (events != null) return events
            logger.debug { "Insel static query $hash is not the programme artefact" }
        }
        logger.warn { "None of the ${hashes.size} Insel static queries carries the programme" }
        return emptyList()
    }

    /** The `staticQueryHashes` the page-data artefact lists, in the order Gatsby wrote them. */
    @Suppress("TooGenericExceptionCaught") // A malformed page-data body must degrade to no candidates, never abort the import.
    private fun staticQueryHashes(json: String): List<String> =
        try {
            jsonMapper
                .readTree(json)
                .path(STATIC_QUERY_HASHES)
                .mapNotNull { node -> node.asString().trim().takeIf { it.isNotBlank() } }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Insel page-data for its static-query hashes" }
            emptyList()
        }

    /**
     * Maps a Gatsby page URL onto its page-data artefact:
     * `https://www.inselberlin.de/` → `https://www.inselberlin.de/page-data/index/page-data.json`.
     *
     * Gatsby keys the artefact by the page's own path, so the path is taken verbatim (minus
     * surrounding slashes) and slotted into the fixed `/page-data/<path>/page-data.json` layout. The
     * site root (`/`) is Gatsby's `index` page and is named as such.
     */
    private fun toPageDataUrl(pageUrl: String): String {
        val uri = URI.create(pageUrl)
        val path =
            uri.path
                .orEmpty()
                .trim('/')
                .ifBlank { ROOT_PAGE_NAME }
        return uri.resolve("/$PAGE_DATA_SEGMENT/$path/$PAGE_DATA_FILE").toString()
    }

    /**
     * Maps a static-query hash onto its artefact:
     * `3497155224` → `https://www.inselberlin.de/page-data/sq/d/3497155224.json`.
     */
    private fun toStaticQueryUrl(
        pageUrl: String,
        hash: String
    ): String = URI.create(pageUrl).resolve("/$PAGE_DATA_SEGMENT/$STATIC_QUERY_SEGMENT/$hash.json").toString()

    private companion object {
        /** Directory Gatsby publishes its query results under. */
        const val PAGE_DATA_SEGMENT = "page-data"

        /** Sub-directory Gatsby publishes static-query results under, keyed by query hash. */
        const val STATIC_QUERY_SEGMENT = "sq/d"

        /** Filename of a Gatsby page-data artefact. */
        const val PAGE_DATA_FILE = "page-data.json"

        /** Gatsby's name for the site root's page-data directory. */
        const val ROOT_PAGE_NAME = "index"

        /** The page-data key listing the static queries the page depends on. */
        const val STATIC_QUERY_HASHES = "staticQueryHashes"
    }
}

val INSEL_LIMITATIONS =
    VenueLimitations(
        EventSource.INSEL,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue names no prices anywhere; only an Eintritt-frei note on the free Sunday matinées"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a dropped show is removed from the CMS rather than flagged"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every event points at the programme page and takes its identity from its date plus its title"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue publishes no category, so a title that is an event name rather than an act is minted as a concert"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "a support act billed without a colon reads as prose, so only a colon or a line-leading support marker is followed"
        )
    )
