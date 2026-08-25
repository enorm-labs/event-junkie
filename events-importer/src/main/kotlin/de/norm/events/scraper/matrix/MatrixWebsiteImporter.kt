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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Matrix Club Berlin — a WordPress club running a resident night every day of
 * the year, whose `/party-in-berlin/` programme is paginated one calendar month at a time
 * (`?get_month=<m>&get_year=<yyyy>`).
 *
 * The entry URL serves the **current** month, listing only the days still to come; this importer
 * then follows the page's own next-month link forward until the venue stops offering one — the month
 * after the last announced night renders "Bisher keine Events eingetragen" and drops the link, which
 * terminates the walk on the venue's own signal rather than on a guessed horizon.
 * [MAX_MONTH_PAGES] caps the walk regardless, so a markup change that made the link self-referential
 * cannot spin.
 *
 * The venue also publishes per-event `/parties/<date>-matrix-<weekday>/` pages, but they carry
 * nothing the month view lacks — so walking ~4 month pages replaces ~90 detail fetches per run.
 *
 * Conditional requests are intentionally **not** used: the site sends neither ETag nor Last-Modified,
 * and even if it did, a 304 on the entry page would say nothing about the later months. Every run
 * re-fetches and relies on idempotent `sourceId` upserts — [ImportResult.Success] is returned with
 * `null` cache headers (there is no `NotModified` path).
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
        val events = mutableListOf<ScrapedEvent>()
        val visited = mutableSetOf<String>()
        var pageUrl: String? = url

        while (pageUrl != null && visited.size < MAX_MONTH_PAGES && visited.add(pageUrl)) {
            val document = htmlFetcher.fetchDocument(pageUrl)
            events += overviewPageScraper.scrape(document, pageUrl)
            pageUrl = nextMonthUrl(document, pageUrl)
        }

        val distinct = events.distinctBy { it.sourceId }
        logger.info { "Scraped ${distinct.size} Matrix event(s) across ${visited.size} month page(s) from $url" }
        return ImportResult.Success(events = distinct, etag = null, lastModified = null)
    }

    /**
     * Resolves the next-month link — the right-hand chevron in the month switcher — or null on the
     * first month the venue has no programme for, which is where the walk stops.
     */
    private fun nextMonthUrl(
        document: Document,
        pageUrl: String
    ): String? = document.attrAt("a:has(i.fa-chevron-right)", "href")?.let { resolveUrl(pageUrl, it) }

    private companion object {
        /**
         * Upper bound on the month pages walked in one run. Matrix announces roughly three months
         * ahead, so this is a runaway guard rather than a horizon — it only bites if the venue's
         * next-month links ever stop terminating.
         */
        private const val MAX_MONTH_PAGES = 12
    }
}

/** Nothing this source withholds needs declaring (#715). */
val MATRIX_LIMITATIONS = VenueLimitations(EventSource.MATRIX)
