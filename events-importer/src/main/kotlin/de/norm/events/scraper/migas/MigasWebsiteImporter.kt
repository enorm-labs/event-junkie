package de.norm.events.scraper.migas

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for migas, a listening bar in Wedding, whose custom WordPress theme renders
 * the whole upcoming programme onto one page (`/program/`).
 *
 * Every event's full record — title, category, ISO start datetime, poster, permalink and blurb —
 * is already in that page's markup, inside a per-event modal, so one request per import is enough
 * and there are no detail pages to follow. All parsing lives in [MigasOverviewPageScraper].
 *
 * **Conditional requests are deliberately disabled.** The page advertises a `Last-Modified` (there
 * is no `ETag`) and the server **does** honour
 * `If-Modified-Since` against it — but the header tracks when the page was last *edited*, while the
 * event list itself is filtered server-side to *upcoming* events and therefore rolls forward every
 * day on its own. Observed on 5 August 2026: `Last-Modified` was three days stale while the listing
 * already started at that day's event. Replaying the stored value would return 304 for as long as
 * nobody edits the page, freezing the programme — events that have passed would never drop and newly
 * announced ones would never arrive. The [etag] and [lastModified] arguments are accepted to satisfy
 * [EventImporter] and then ignored: every run re-fetches unconditionally and relies on idempotent
 * `sourceId` upserts, returning `null` cache headers so nothing is stored for the next run (there is
 * no [ImportResult.NotModified] path). This is the same reasoning that disables them for Havanna's
 * derived weekly occurrences.
 *
 * **Only the first page is imported.** The listing pages at 10 events, with the remainder behind a
 * "Load More" button that POSTs
 * `action=load_events&paged=<n>` to `wp-admin/admin-ajax.php` (a GET ignores `paged` and re-serves
 * page 1). Following it is declined per ADR-007 §"Pagination — First Page Only": it would need a
 * POST transport that the shared [HtmlFetcher] does not have, for a venue whose programme runs
 * around a dozen events. The cost is bounded and self-correcting — the page always holds the *next*
 * ten upcoming events, so as each night passes the later ones move onto it, and with a daily import
 * every event is captured well before it happens. Stale-event cleanup is scoped to the scraped date
 * range, so the untouched tail is never mistaken for a deletion.
 *
 * @see MigasOverviewPageScraper for the page shape, the lazy-loaded image trap, and what the source
 *   does not publish.
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
        val events = overviewPageScraper.scrape(document)
        logger.info { "Scraped ${events.size} event(s) from migas" }

        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }
}
