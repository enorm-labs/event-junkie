package de.norm.events.scraper.migas

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
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
 * **Conditional requests are deliberately disabled.** The page advertises a `Last-Modified` and the
 * server honours `If-Modified-Since` against it, but the header tracks when the page was last
 * *edited* while the listing is filtered server-side to upcoming events and rolls forward on its own
 * — so a stored value returns 304 for as long as nobody edits the page, freezing the programme in
 * both directions. [etag] and [lastModified] are accepted to satisfy [EventImporter] and ignored:
 * every run re-fetches and relies on idempotent `sourceId` upserts, returning `null` cache headers so
 * there is nothing to replay (and no [ImportResult.NotModified] path). Havanna's derived weekly
 * occurrences are disabled for the same reason.
 *
 * **Only the first page is imported**, per ADR-007 §"Pagination — First Page Only": the rest sits
 * behind a button that POSTs to `wp-admin/admin-ajax.php`, which the shared [HtmlFetcher] has no
 * transport for. The cost is bounded and self-correcting — the page always holds the *next* ten
 * upcoming events, so a later night moves onto it as each one passes, and a daily import catches
 * every event well before it happens. Stale-event cleanup is scoped to the scraped date range, so
 * the untouched tail is never mistaken for a deletion.
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

val MIGAS_LIMITATIONS =
    VenueLimitations(
        EventSource.MIGAS,
        AcceptedLimitation(LimitedAspect.PRICE, "entry arrangements are not stated on the site at all"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entry arrangements are not stated on the site at all"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the listing carries no door time"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the listing carries no sold-out badge"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the listing carries no cancellation badge"),
        AcceptedLimitation(LimitedAspect.PAGINATION, "the listing pages at ten events, with the rest behind a Load More button that POSTs to `admin-ajax.php`")
    )
