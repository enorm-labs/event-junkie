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
import de.norm.events.scraper.scrapeListingPages
import de.norm.events.scraper.withEventPageOrFlagged
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
 * degrades to the row's own data, flagged so the upsert keeps the stored detail fields (see #2425).
 * Conditional requests are not used: the entry page's `ETag` covers page 1 alone, and a `304`
 * would freeze the pages behind it. The park's participation formats are not imported ([isProgrammeCategory]). An exhibition is one run: listed once per
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
        val listing =
            htmlFetcher.scrapeListingPages(
                eventSource,
                htmlFetcher.fetchDocument(url),
                url,
                MAX_PAGES,
                overviewPageScraper::nextPageUrl,
                overviewPageScraper::scrape
            )
        // An exhibition is folded first, so its page is fetched once (ADR-029, #337).
        val rows =
            listing.events.collapseExhibitionRuns { row ->
                parseEventPath(row.sourceUrl)?.let { "${EventSource.GAERTEN_DER_WELT.sourceIdPrefix}${it.slug}" }
            }
        val events = rows.map { enrichFromDetailPage(it) }
        logger.info { "Scraped ${events.size} Gärten der Welt event(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = listing.complete)
    }

    /**
     * The row's detail page merged over the row. A page that yields nothing leaves the row, which
     * carries title, date, start time, category, teaser, poster and ticket link, flagged
     * [ScrapedEvent.detailUnavailable].
     */
    private suspend fun enrichFromDetailPage(row: ScrapedEvent): ScrapedEvent =
        htmlFetcher.withEventPageOrFlagged(row) { document ->
            detailPageScraper.scrape(document, row.sourceUrl)?.let { merge(detail = it, row = row) }
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
        return detail.withGapsFrom(row).copy(
            eventDate = row.eventDate,
            startTime = row.startTime,
            eventType = row.eventType,
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
