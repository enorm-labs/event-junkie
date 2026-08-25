package de.norm.events.scraper.humboldthain

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Humboldthain Club Berlin.
 *
 * The WordPress site (`humboldthain.com`) publishes its programme only through an embedded
 * Elfsight "Event Calendar" widget, which renders client-side — the served HTML contains no
 * events at all. The widget's public boot API, however, returns the venue's whole calendar as
 * clean structured JSON: the most stable possible source (ADR-007 §"Selector Strategy" —
 * structured data is priority 1), reachable without a headless browser. This importer:
 * 1. Fetches the widget boot JSON via [ApiClient.fetchJson] (shared politeness throttle and
 *    identifying User-Agent). The configured `url` is the boot endpoint carrying the widget id
 *    (`core.service.elfsight.com/p/boot/?w=<widgetId>`) and is used verbatim — all events come
 *    back in the single response (ADR-007 first-page-only).
 * 2. Parses it into [de.norm.events.scraper.ScrapedEvent]s via [HumboldthainApiScraper],
 *    expanding the venue's weekly resident night into one event per occurrence.
 *
 * Conditional requests are deliberately unused, for two independent reasons. The Elfsight boot
 * API sends an ETag, but honouring it would freeze the recurrence horizon: the resident night's
 * occurrences are derived from "today", so a 304 on an unchanged calendar would stop that window
 * advancing (the same reason [de.norm.events.scraper.havanna.HavannaWebsiteImporter] re-fetches
 * every run). The `etag` / `lastModified` parameters are therefore ignored and every import
 * returns [ImportResult.Success] with `null` cache headers — never [ImportResult.NotModified].
 * Re-imports stay cheap and safe because persistence upserts idempotently by `sourceId`.
 *
 * @see HumboldthainApiScraper for the JSON parsing and recurrence expansion.
 * @see <a href="https://www.humboldthain.com/">Humboldthain Club</a>
 */
@Component
class HumboldthainWebsiteImporter(
    private val apiClient: ApiClient,
    /** Clock anchoring the rolling recurrence horizon. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.HUMBOLDTHAIN

    private val apiScraper = HumboldthainApiScraper(clock)

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val json = apiClient.fetchJson(url)
        val events = apiScraper.scrape(json)
        logger.info { "Scraped ${events.size} event(s) from Humboldthain Club" }

        // Conditional requests would freeze the recurrence horizon, so there is no NotModified
        // path; ETag / Last-Modified are always null and change detection relies on idempotent upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }
}

val HUMBOLDTHAIN_LIMITATIONS =
    VenueLimitations(
        EventSource.HUMBOLDTHAIN,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the calendar widget exposes no per-event URLs"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices appear only in the prose, in too many spellings to parse"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "nothing in the payload marks a night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "nothing in the payload marks a night cancelled or moved")
    )
