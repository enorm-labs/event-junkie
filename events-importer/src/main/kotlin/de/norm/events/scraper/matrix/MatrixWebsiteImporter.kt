package de.norm.events.scraper.matrix

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.matrix.MatrixWebsiteImporter.Companion.MAX_MONTH_PAGES
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.scrapeListingPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Matrix Club Berlin — a WordPress club running a resident night every day
 * of the year, whose `/party-in-berlin/` programme is paginated one calendar month at a time
 * (`?get_month=<m>&get_year=<yyyy>`).
 *
 * The entry URL serves the **current** month, listing only the days still to come; the importer
 * follows the page's own next-month link to the first month that renders "Bisher keine Events
 * eingetragen". That page still offers a next link, to every month ahead, so the walk ends on the
 * marker and not on the link (#2319). Matrix is open every night, so an empty month is the end of
 * the programme, not a gap. [MAX_MONTH_PAGES] caps the walk regardless, so a self-referential link
 * cannot spin.
 *
 * Per-event `/parties/<date>-matrix-<weekday>/` pages exist but carry nothing the month view
 * lacks — walking ~4 month pages replaces ~90 detail fetches per run.
 *
 * Conditional requests are intentionally **not** used: the site sends neither ETag nor
 * Last-Modified, and a 304 on the entry page would say nothing about the later months. Every
 * run re-fetches and relies on idempotent `sourceId` upserts — [ImportResult.Success] with
 * `null` cache headers (no `NotModified` path).
 *
 * @see MatrixOverviewPageScraper for the per-month parsing.
 * @see <a href="https://www.matrix-berlin.de/party-in-berlin/">Matrix programme page</a>
 */
@Component
class MatrixWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MATRIX

    private val overviewPageScraper = MatrixOverviewPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing =
            htmlFetcher.scrapeListingPages(
                eventSource,
                htmlFetcher.fetchDocument(url),
                url,
                MAX_MONTH_PAGES,
                ::nextMonthUrl,
                overviewPageScraper::scrape
            )
        logger.info { "Scraped ${listing.events.size} Matrix event(s) from $url" }
        return ImportResult.Success(events = listing.events, etag = null, lastModified = null, complete = listing.complete)
    }

    /**
     * The next-month link — the right-hand chevron in the month switcher — or null on the first
     * month with no programme, where the walk stops.
     */
    private fun nextMonthUrl(
        document: Document,
        pageUrl: String
    ): String? =
        if (document.selectFirst(EMPTY_MONTH) != null) {
            null
        } else {
            document.attrAt("a:has(i.fa-chevron-right)", "href")?.let { resolveUrl(pageUrl, it) }
        }

    private companion object {
        /**
         * Upper bound on month pages per run. Matrix announces roughly three months ahead, so a
         * runaway guard rather than a horizon — it bites only if next-month links stop terminating.
         */
        private const val MAX_MONTH_PAGES = 12

        /** The heading a month without a programme renders in place of its nights. */
        private const val EMPTY_MONTH = "h2:containsOwn(Bisher keine Events eingetragen)"
    }
}

/** Nothing this source withholds needs declaring (#715). */
val MATRIX_LIMITATIONS = VenueLimitations(EventSource.MATRIX)
