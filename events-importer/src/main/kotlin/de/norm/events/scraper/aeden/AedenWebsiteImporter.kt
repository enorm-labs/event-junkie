package de.norm.events.scraper.aeden

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for ÆDEN Berlin — a WordPress techno club whose `/events` entry page holds no
 * events at all, only one `a.month-button` per upcoming month. Each button links to a fully
 * server-rendered `/month/?month=YYYY-MM` page; this importer discovers those links from the entry
 * page, fetches each month, and parses its nights via [AedenOverviewPageScraper].
 *
 * Conditional requests are intentionally **not** used: the entry page changes only when a month is
 * added or drops off, never when a night inside an existing month is edited, so its ETag would mask
 * mid-month edits. Every run re-fetches the entry and month pages and relies on idempotent `sourceId`
 * upserts — [ImportResult.Success] is returned with `null` cache headers (there is no `NotModified`
 * path). Nights that have already passed but are still listed on the current month's page are dropped
 * centrally at persistence time (`EventUpsertService`).
 *
 * Only the club programme is imported: the venue's other spaces live in the `aeve` / `oel` post
 * types, which never appear on the month pages.
 *
 * @see AedenOverviewPageScraper for the per-month parsing.
 * @see <a href="https://aedenberlin.com/events/">ÆDEN events page</a>
 */
@Component
class AedenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.AEDEN

    private val overviewPageScraper = AedenOverviewPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val entry = htmlFetcher.fetchDocument(url)
        val monthUrls = discoverMonthUrls(entry, url)
        logger.info { "Found ${monthUrls.size} month page(s) linked from ÆDEN entry $url" }

        val events =
            monthUrls
                .flatMap { monthUrl -> overviewPageScraper.scrape(htmlFetcher.fetchDocument(monthUrl), monthUrl) }
                .distinctBy { it.sourceId }
        logger.info { "Scraped ${events.size} ÆDEN event(s) across ${monthUrls.size} month page(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Resolves every distinct month link on the entry page to an absolute URL. The buttons are
     * absolute (`https://aedenberlin.com/month/?month=2026-08`) in the rendered markup, but are
     * resolved defensively so a relative href would work too.
     */
    private fun discoverMonthUrls(
        entry: Document,
        entryUrl: String
    ): List<String> =
        entry
            .select("a.month-button[href]")
            .map { it.attr("href") }
            .filter { it.isNotBlank() }
            .map { resolveUrl(entryUrl, it) }
            .distinct()
}

val AEDEN_LIMITATIONS =
    VenueLimitations(
        EventSource.AEDEN,
        AcceptedLimitation(LimitedAspect.PRICE, "the month page carries no prices"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the month page links no page per night")
    )
