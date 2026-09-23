package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.urbanspree.UrbanSpreeWebsiteImporter.Companion.MAX_PAGES
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Urban Spree Berlin, the RAW-Gelände art gallery and concert venue on
 * MODX with a pdoTools-paginated `/program/` listing. The one importer that needs multi-page
 * crawling (ADR-007 §"Pagination — First Page Only"): the listing sorts descending by date
 * across its whole archive (200+ pages of nine cards), so page 1 holds the farthest future
 * shows and the coming months sit on pages 2, 3, 4 … The importer walks forward and stops at
 * the first page that reaches the past, bounded by [MAX_PAGES] in case the ordering changes.
 *
 * Each card is enriched from its detail page, since the listing truncates titles and carries no
 * promoter, ticket link or description; a failed detail fetch degrades to the card. Conditional
 * requests are not used: the site answers `Cache-Control: no-store` with neither `ETag` nor
 * `Last-Modified`, so [ImportResult.Success] returns `null` cache headers and the run relies on
 * idempotent `sourceId` upserts.
 *
 * @see UrbanSpreeOverviewPageScraper for listing-page parsing (discovery, date, poster).
 * @see UrbanSpreeDetailPageScraper for the primary per-event data source.
 * @see <a href="https://www.urbanspree.com/program/">Urban Spree programme</a>
 */
@Component
class UrbanSpreeWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the "has this page reached the past?" pagination cutoff. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.URBAN_SPREE

    private val overviewPageScraper = UrbanSpreeOverviewPageScraper()
    private val detailPageScraper = UrbanSpreeDetailPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val upcoming = collectUpcomingCards(url)
        val events = upcoming.map { enrichFromDetailPage(it) }
        logger.info { "Scraped ${events.size} Urban Spree event(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Walks `?page=N` forward from [entryUrl], collecting every card dated today or later, stopping
     * at the first page with a past-dated card: newest-first, so everything beyond is archive. Past
     * cards on the boundary page are dropped here, sparing a detail fetch.
     */
    private suspend fun collectUpcomingCards(entryUrl: String): List<ScrapedEvent> {
        val today = LocalDate.now(clock)
        val collected = mutableListOf<ScrapedEvent>()
        var page = 1
        var listingContinues = true

        while (listingContinues && page <= MAX_PAGES) {
            val pageUrl = pageUrl(entryUrl, page)
            val cards = overviewPageScraper.scrape(htmlFetcher.fetchDocument(pageUrl), pageUrl)
            val (upcoming, past) = cards.partition { !it.eventDate.isBefore(today) }
            collected += upcoming

            // An empty page means the listing ran out; a past-dated card means the boundary.
            listingContinues = cards.isNotEmpty() && past.isEmpty()
            if (!listingContinues) {
                logger.info { "Ending Urban Spree pagination at page $page (${cards.size} card(s), ${past.size} already past)" }
            }
            page++
        }

        if (listingContinues) {
            logger.warn { "Urban Spree pagination hit the $MAX_PAGES-page cap before reaching the past; some events may be missing" }
        }
        return collected.distinctBy { it.sourceId }
    }

    /**
     * The listing URL for [page], page 1 left as the entry URL; the query separator adapts to an
     * entry URL that already carries a query string.
     */
    private fun pageUrl(
        entryUrl: String,
        page: Int
    ): String {
        if (page == 1) return entryUrl
        val separator = entryUrl.querySeparator()
        return "$entryUrl$separator$PAGE_QUERY_PARAM=$page"
    }

    /**
     * Fetches and parses the card's detail page, merging it over the card; any failure degrades to
     * the card, which carries title, date, type, price and poster.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: degrade to overview data if the detail page is unavailable
    private suspend fun enrichFromDetailPage(card: ScrapedEvent): ScrapedEvent =
        try {
            val document = htmlFetcher.fetchDocument(card.sourceUrl)
            detailPageScraper.scrape(document, card.sourceUrl)?.let { merge(detail = it, card = card) } ?: card
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch Urban Spree detail page for '${card.title}' (${card.sourceUrl}), using listing data" }
            card
        }

    /**
     * Merges a parsed [detail] page over its listing [card]. The detail page owns the untruncated
     * title, description, promoter and ticket link; the card wins on date and start time
     * (`data-dateStart`) and on image (the original upload rather than the detail page's
     * cache-keyed `phpthumbof` thumbnail) (ADR-007 §"Selector Strategy"). Everything else falls
     * back to the card only where the detail page supplied nothing.
     */
    private fun merge(
        detail: ScrapedEvent,
        card: ScrapedEvent
    ): ScrapedEvent =
        detail.copy(
            eventDate = card.eventDate,
            startTime = card.startTime ?: detail.startTime,
            eventType = detail.eventType ?: card.eventType,
            imageUrl = card.imageUrl ?: detail.imageUrl,
            pricePresale = detail.pricePresale ?: card.pricePresale,
            free = detail.free || card.free,
            status = detail.status.takeIf { it != EventStatus.SCHEDULED.name } ?: card.status
        )

    private companion object {
        /**
         * Upper bound on the walk: the venue books roughly a year ahead, well under twenty pages, while
         * the archive runs to 200+. A runaway guard, logged as a warning.
         */
        private const val MAX_PAGES = 20

        /** pdoPage's page query parameter, as declared in the listing's `pdoPage.initialize({...pageVarKey…})` config. */
        private const val PAGE_QUERY_PARAM = "page"
    }
}

/** Nothing this source withholds needs declaring (#715). */
val URBAN_SPREE_LIMITATIONS =
    VenueLimitations(
        EventSource.URBAN_SPREE,
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue credits itself as the organiser on its own nights, so the stored promoter is the venue")
    )
