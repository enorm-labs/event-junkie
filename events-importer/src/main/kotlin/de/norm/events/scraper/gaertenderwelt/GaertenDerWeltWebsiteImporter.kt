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
import de.norm.events.scraper.collapseExhibitionRuns
import de.norm.events.scraper.gaertenderwelt.GaertenDerWeltWebsiteImporter.Companion.MAX_PAGES
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Gärten der Welt Berlin, the Marzahn landscape park whose Arena stages
 * open-air concerts, on TYPO3 with the `events2` extension. The listing is paginated five rows
 * to a page, the escape hatch ADR-007 §"Pagination — First Page Only" leaves open: the programme
 * runs eight to nine pages ahead, so page one alone would yield a fortnight. The importer walks
 * the paginator's own "nächste" link until the last page renders none, bounded by [MAX_PAGES];
 * counting `/pageN/` URLs would not terminate, since TYPO3 clamps an out-of-range page to the
 * last one and answers `200`.
 *
 * Each row is enriched from its detail page (description, prices, doors, promoter); a failure
 * degrades to the row's own data. Conditional requests are not used: the entry page's `ETag`
 * covers page 1 alone, and a `304` would freeze the pages behind it. The park's participation
 * formats are not imported ([isProgrammeCategory]). An exhibition is one run: listed once per
 * open day under one slug, folded from first day to last ([collapseExhibitionRuns], ADR-029,
 * #337); a drone show over three nights keeps its nights, since only `EXHIBITION` rows fold.
 *
 * @see GAERTEN_DER_WELT_LIMITATIONS for what the park does not publish.
 * @see GaertenDerWeltOverviewPageScraper for listing-page parsing, identity, date and pagination.
 * @see GaertenDerWeltDetailPageScraper for the description, prices, doors time and promoter.
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
        // An exhibition is folded first, so its page is fetched once (ADR-029, #337).
        val rows =
            collectListingRows(url).collapseExhibitionRuns { row ->
                parseEventPath(row.sourceUrl)?.let { "${EventSource.GAERTEN_DER_WELT.sourceIdPrefix}${it.slug}" }
            }
        val events = rows.map { enrichFromDetailPage(it) }
        logger.info { "Scraped ${events.size} Gärten der Welt event(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Walks the listing from [entryUrl], following each page's "nächste" link. [MAX_PAGES] is a
     * runaway guard; hitting it means the paginator stopped ending, logged as a warning.
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
     * Fetches and parses the row's detail page, merging it over the row. Any failure degrades to the
     * row alone, which carries title, date, start time, category, teaser, poster and ticket link.
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
     * Merges a parsed [detail] page over its listing [row]. The detail page owns the description,
     * prices, doors time, promoter and full-size poster; the row wins on date and start time (the
     * URL stamp, where the detail page renders a year-less "Samstag, 08.08.") and on event type (the
     * `.category` label the single view does not repeat) (ADR-007 §"Selector Strategy"). Artists
     * are built last, because the type decides whether the title names an act.
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
         * Upper bound on the pagination walk: roughly nine pages of five rows today, with headroom.
         * Hitting it is logged as a warning.
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
