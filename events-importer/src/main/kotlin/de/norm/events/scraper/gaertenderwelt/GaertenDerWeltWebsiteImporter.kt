package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.gaertenderwelt.GaertenDerWeltWebsiteImporter.Companion.MAX_PAGES
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Gärten der Welt Berlin — the Marzahn landscape park whose Arena stages
 * open-air concerts, running on TYPO3 with the `events2` extension.
 *
 * The listing is server-rendered but **paginated five rows to a page**, which puts it in the escape
 * hatch ADR-007 §"Pagination — First Page Only" leaves open: the programme runs eight to nine pages
 * ahead, ascending by date, so importing the first page would yield the next fortnight and nothing
 * else. The importer walks the paginator's own "nächste" link until the last page renders none,
 * bounded by [MAX_PAGES].
 *
 * **Following the rendered link, rather than counting `/pageN/` URLs, is what makes the walk
 * terminate.** TYPO3 clamps an out-of-range page number to the last page and answers `200` with
 * its rows repeated, so a counting walk would never see an end and would run to its own cap on
 * every import.
 *
 * Each surviving row is then enriched from its detail page, which is where the park writes the
 * description, the prices, the doors time and the promoter. A detail page that fails to fetch or
 * parse degrades to the row's own data rather than failing the import.
 *
 * Conditional requests are intentionally **not** used: the entry page's `ETag` covers page 1 alone, so
 * a `304` there would freeze the eight pages behind it, including every event further out than the
 * next fortnight. [ImportResult.Success] is returned with `null` cache headers and the run relies on
 * idempotent `sourceId` upserts.
 *
 * **The park's participation formats are deliberately not imported** — see [isProgrammeCategory].
 *
 * **A multi-day run is stored as its opening date.** The park lists a run under one row with a date
 * range — an exhibition across two months, a drone show over three nights, a workshop over a weekend
 * — and the model holds one date per event. The URL stamp gives the opening unambiguously, so that
 * is what is stored; expanding a range into one event per day would invent sixty openings for a
 * two-month exhibition and cannot be told from a genuine multi-night booking.
 *
 * @see GaertenDerWeltOverviewPageScraper for listing-page parsing, identity, date and pagination.
 * @see GaertenDerWeltDetailPageScraper for the description, prices, doors time and promoter.
 * @see <a href="https://www.gaertenderwelt.de/events/veranstaltungen/">Gärten der Welt programme</a>
 */
@Component
class GaertenDerWeltWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.GAERTEN_DER_WELT

    private val overviewPageScraper = GaertenDerWeltOverviewPageScraper()
    private val detailPageScraper = GaertenDerWeltDetailPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val rows = collectListingRows(url)
        val events = rows.map { enrichFromDetailPage(it) }
        logger.info { "Scraped ${events.size} Gärten der Welt event(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Walks the listing from [entryUrl], following each page's "nächste" link, and returns every
     * in-scope row it found. The [MAX_PAGES] bound is a runaway guard rather than an expected
     * limit — hitting it means the paginator stopped ending, and is logged as a warning.
     */
    private suspend fun collectListingRows(entryUrl: String): List<ScrapedEvent> {
        val collected = mutableListOf<ScrapedEvent>()
        var pageUrl: String? = entryUrl
        var page = 0

        while (pageUrl != null && page < MAX_PAGES) {
            val document = htmlFetcher.fetchDocument(pageUrl)
            collected += overviewPageScraper.scrape(document, pageUrl)
            pageUrl = overviewPageScraper.nextPageUrl(document, pageUrl)
            page++
        }

        if (pageUrl != null) {
            logger.warn { "Gärten der Welt pagination hit the $MAX_PAGES-page cap before the listing ended; later pages were not read" }
        }
        return collected.distinctBy { it.sourceId }
    }

    /**
     * Fetches and parses the row's detail page, merging it over the row's own data. Any failure —
     * an unreachable page, a redesigned template — degrades to the row alone, which already
     * carries a title, date, start time, category, teaser, poster and ticket link.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: degrade to listing data if the detail page is unavailable
    private suspend fun enrichFromDetailPage(row: ScrapedEvent): ScrapedEvent =
        try {
            val document = htmlFetcher.fetchDocument(row.sourceUrl)
            detailPageScraper.scrape(document, row.sourceUrl)?.let { merge(detail = it, row = row) } ?: row
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch Gärten der Welt detail page for '${row.title}' (${row.sourceUrl}), using listing data" }
            row
        }

    /**
     * Merges a parsed [detail] page over its listing [row].
     *
     * The detail page is the primary source — it owns the description, the prices, the doors time,
     * the promoter and the full-size poster. The row wins on the two things the detail page cannot
     * express (ADR-007 §"Selector Strategy"):
     *  - **date and start time**, read from the URL stamp, where the detail page renders a
     *    year-less "Samstag, 08.08." and a multi-day run as a range; and
     *  - **event type**, from the `.category` label, which the single view does not repeat.
     *
     * Artists are built last, because the type is what decides whether the title names an act at
     * all: a concert's title is its headliner, a park festival's is not.
     */
    private fun merge(
        detail: ScrapedEvent,
        row: ScrapedEvent
    ): ScrapedEvent {
        val subtitle = detail.subtitle ?: row.subtitle
        return detail.copy(
            eventDate = row.eventDate,
            startTime = row.startTime,
            eventType = row.eventType,
            subtitle = subtitle,
            imageUrl = detail.imageUrl ?: row.imageUrl,
            ticketUrl = detail.ticketUrl ?: row.ticketUrl,
            soldOut = detail.soldOut || row.soldOut,
            status = detail.status.takeIf { it != EventStatus.SCHEDULED.name } ?: row.status,
            artists = buildArtistsForEventType(detail.title, subtitle = subtitle, eventType = row.eventType)
        )
    }

    private companion object {
        /**
         * Upper bound on the pagination walk. The park publishes roughly nine pages of five rows
         * at a time, so this leaves ample headroom for a busier season while keeping a runaway
         * loop impossible if the paginator ever stops ending. Hitting it is logged as a warning.
         */
        private const val MAX_PAGES = 40
    }
}

val GAERTEN_DER_WELT_LIMITATIONS =
    VenueLimitations(
        EventSource.GAERTEN_DER_WELT,
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the park's only classification is the format category the event type is already built from; it names no musical style, not even in prose"
        )
    )
