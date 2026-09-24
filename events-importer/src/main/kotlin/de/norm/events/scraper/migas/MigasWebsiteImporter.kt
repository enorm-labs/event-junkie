package de.norm.events.scraper.migas

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Website importer for migas, a listening bar in Wedding, whose custom WordPress theme renders
 * the whole upcoming programme onto one page (`/program/`).
 *
 * Every event's full record — title, category, ISO start datetime, poster, permalink, blurb —
 * is in that page's markup inside a per-event modal, so one request per import and no detail
 * pages. All parsing lives in [MigasOverviewPageScraper].
 *
 * **Conditional requests are deliberately disabled.** The page advertises a `Last-Modified` and
 * the server honours `If-Modified-Since`, but the header tracks when the page was last *edited*
 * while the listing is filtered server-side to upcoming events and rolls forward on its own —
 * a stored value returns 304 for as long as nobody edits the page, freezing the programme in
 * both directions. [etag] and [lastModified] are accepted to satisfy [EventImporter] and
 * ignored: every run re-fetches and relies on idempotent `sourceId` upserts, returning `null`
 * cache headers so there is nothing to replay (and no [ImportResult.NotModified] path).
 * Havanna's derived weekly occurrences are disabled for the same reason.
 *
 * **Every page is read.** The page shows ten events. Its "Load More" button POSTs
 * `action=load_events&paged=<n>&type=upcoming` to `wp-admin/admin-ajax.php`, and its `data-pages`
 * states the page count, which bounds the loop. The answer is a fragment of items and their modals,
 * added to the page's `.events-list` as the site's own script does, so the scraper reads one
 * document. A later page that fails is logged and skipped: stale-event cleanup is scoped to the
 * scraped date range, so the unread tail is never mistaken for a deletion (#331).
 *
 * @see MigasOverviewPageScraper for the page shape, the lazy-loaded image trap, and what the
 * source does not publish.
 * @see <a href="https://migas.berlin/program/">migas programme</a>
 */
@Component
class MigasWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MIGAS

    private val overviewPageScraper = MigasOverviewPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val document = htmlFetcher.fetchDocument(url)
        val pages = appendLaterPages(document, url)
        val events = overviewPageScraper.scrape(document)
        logger.info { "Scraped ${events.size} event(s) from $pages migas page(s)" }

        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /** Adds pages `2..data-pages` to [document]'s list and returns how many pages it now holds. */
    @Suppress("TooGenericExceptionCaught") // Intentional: keep the pages already read if a later one fails
    private suspend fun appendLaterPages(
        document: Document,
        url: String
    ): Int {
        val list = document.selectFirst(EVENTS_LIST_SELECTOR)
        val pageCount =
            document
                .selectFirst(LOAD_MORE_SELECTOR)
                ?.attr("data-pages")
                ?.toIntOrNull()
                ?.coerceAtMost(MAX_PAGES)
                ?.takeIf { list != null } ?: 1
        val ajaxUrl = URI(url).resolve(AJAX_PATH).toString()
        var read = 1
        while (read < pageCount) {
            val page = read + 1
            val fragment =
                try {
                    htmlFetcher.postForm(ajaxUrl, mapOf("action" to "load_events", "paged" to "$page", "type" to "upcoming"))
                } catch (e: Exception) {
                    logger.warn(e) { "migas page $page of $pageCount failed; importing the $read page(s) read" }
                    break
                }
            list?.append(fragment)
            read = page
        }
        return read
    }

    private companion object {
        const val EVENTS_LIST_SELECTOR = ".events-list"
        const val LOAD_MORE_SELECTOR = "[data-target=load-more]"
        const val AJAX_PATH = "/wp-admin/admin-ajax.php"

        /** A runaway guard: the site states two pages. */
        const val MAX_PAGES = 10
    }
}

val MIGAS_LIMITATIONS =
    VenueLimitations(
        EventSource.MIGAS,
        AcceptedLimitation(LimitedAspect.PRICE, "entry arrangements are not stated on the site at all"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entry arrangements are not stated on the site at all"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the listing carries no door time"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the listing carries no sold-out badge"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the listing carries no cancellation badge")
    )
