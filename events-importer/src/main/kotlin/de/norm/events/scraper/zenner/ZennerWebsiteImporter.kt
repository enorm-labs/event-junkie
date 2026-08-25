package de.norm.events.scraper.zenner

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Website importer for Zenner Berlin.
 *
 * Zenner's `/programm` page is a Gatsby front end over a Sanity headless CMS: the served
 * HTML is a React shell whose event markup comes from a GraphQL query. Gatsby also
 * publishes that query's result as a static JSON artefact next to the page, so this
 * importer:
 * 1. Derives the artefact URL from the configured programme page ([toPageDataUrl]).
 * 2. Fetches the JSON body via [ApiClient.fetchJson] (shared politeness throttle and
 *    identifying User-Agent).
 * 3. Parses it into [de.norm.events.scraper.ScrapedEvent]s via [ZennerApiScraper], passing
 *    the programme page as each event's `sourceUrl` — the venue has no per-event pages.
 *
 * The **programme page** is what the event source stores, not the artefact URL: it is the
 * venue's real, user-facing entry point and doubles as the events' `sourceUrl`, and the
 * `/page-data/<path>/page-data.json` layout it maps onto is a fixed Gatsby convention
 * rather than a per-venue detail (ADR-007: entry-point URL in config, derivation in code).
 *
 * No ETag / Last-Modified conditional request is used — the `etag` / `lastModified`
 * parameters are ignored and every import returns [ImportResult.Success]. The artefact is
 * regenerated on every site rebuild, so its validators track the build rather than the
 * programme, and re-imports stay cheap because persistence upserts idempotently by
 * `sourceId`.
 *
 * @see ZennerApiScraper for the JSON parsing logic.
 * @see <a href="https://zenner.berlin/programm">Zenner Programm</a>
 */
@Component
class ZennerWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ZENNER

    private val apiScraper = ZennerApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val json = apiClient.fetchJson(toPageDataUrl(url))
        val events = apiScraper.scrape(json, url)
        logger.info { "Scraped ${events.size} event(s) from Zenner" }

        // Gatsby's page-data artefact is rebuilt whenever the site is, so its ETag / Last-Modified
        // track the build rather than the programme; change detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Maps a Gatsby page URL onto its page-data artefact:
     * `https://zenner.berlin/programm` → `https://zenner.berlin/page-data/programm/page-data.json`.
     *
     * Gatsby keys the artefact by the page's own path, so the path is taken verbatim (minus
     * surrounding slashes) and slotted into the fixed `/page-data/<path>/page-data.json`
     * layout. The site root (`/`) is Gatsby's `index` page and is named as such.
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

    private companion object {
        /** Directory Gatsby publishes its per-page query results under. */
        const val PAGE_DATA_SEGMENT = "page-data"

        /** Filename of a Gatsby page-data artefact. */
        const val PAGE_DATA_FILE = "page-data.json"

        /** Gatsby's name for the site root's page-data directory. */
        const val ROOT_PAGE_NAME = "index"
    }
}

val ZENNER_LIMITATIONS =
    VenueLimitations(
        EventSource.ZENNER,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes no prices"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes no doors times"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue publishes no sold-out state"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the venue publishes no per-event pages")
    )
