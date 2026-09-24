package de.norm.events.scraper.amt

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.absoluteLinksAt
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for AMT Club Berlin — a Webflow techno club whose `/events` entry page
 * carries no events server-side (a Finsweet CMS-nest list injects them client-side). The entry
 * page links to per-month `/month/<name>` pages, each fully server-rendered; this importer
 * discovers those links, fetches each, and parses its events via [AmtOverviewPageScraper].
 *
 * Conditional requests are intentionally **not** used: the entry-page ETag changes only when a
 * month is added, not when a night is edited within one, so relying on it would miss mid-month
 * edits. Every run re-fetches entry and month pages and relies on idempotent `sourceId` upserts
 * — [ImportResult.Success] with `null` cache headers (no `NotModified` path). Past-dated nights
 * left on the current-month page are dropped centrally at persistence (`EventUpsertService`).
 *
 * **The source reads zero because the venue stopped filling it, not because this parser broke**
 * (#1677). The entry page links two months and no more, every later `/month/<name>` answers 404,
 * and the home page's own upcoming-events list renders `No items found.` server-side. Both linked
 * months still parse, so the markup is unchanged. Meanwhile the club is programming and announces
 * its nights on Resident Advisor, which is why the zero is recorded in `KNOWN_QUIET_SOURCES`
 * rather than repaired here. #356 decides whether that listing may be read.
 *
 * @see AmtOverviewPageScraper for the per-month parsing.
 * @see <a href="https://www.club-amt.berlin/events">AMT events page</a>
 */
@Component
class AmtWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.AMT

    private val overviewPageScraper = AmtOverviewPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val entry = htmlFetcher.fetchDocument(url)
        val monthUrls = entry.absoluteLinksAt(MONTH_LINK_SELECTOR, url)
        logger.info { "Found ${monthUrls.size} month page(s) linked from AMT entry $url" }

        val events =
            monthUrls
                .flatMap { monthUrl -> overviewPageScraper.scrape(htmlFetcher.fetchDocument(monthUrl), monthUrl) }
                .distinctBy { it.sourceId }
        logger.info { "Scraped ${events.size} AMT event(s) across ${monthUrls.size} month page(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    private companion object {
        /** The entry page's `/month/<name>` links, one per published month. */
        const val MONTH_LINK_SELECTOR = "a[href^=\"/month/\"]"
    }
}

val AMT_LIMITATIONS =
    VenueLimitations(
        EventSource.AMT,
        AcceptedLimitation(LimitedAspect.ARTISTS, "the DJ line separates names with spaces and nothing else, so it cannot be split apart reliably")
    )
