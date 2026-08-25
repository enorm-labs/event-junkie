package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.urbanspree.UrbanSpreeWebsiteImporter.Companion.MAX_PAGES
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Urban Spree Berlin — the RAW-Gelände art gallery and concert venue,
 * running on MODX with a pdoTools-paginated `/program/` listing.
 *
 * This is the project's one importer that genuinely needs **multi-page crawling**, the
 * escape hatch ADR-007 §"Pagination — First Page Only" leaves open for a site with
 * server-side `?page=N` pagination. Urban Spree sorts its listing **descending by date**
 * across its entire archive (200+ pages of nine cards), so page 1 holds the *farthest*
 * future shows and everything happening in the coming months sits on pages 2, 3, 4 …
 * Importing the first page only would yield a handful of shows a year out and miss every
 * near-term one. So the importer walks forward from the entry URL and stops as soon as a
 * page reaches the past — with the listing in descending order, no later page can hold an
 * upcoming event. [MAX_PAGES] bounds the walk regardless, in case the ordering ever changes.
 *
 * Each surviving card is then enriched from its detail page, because the listing truncates
 * titles with a CSS ellipsis and carries no promoter, ticket link or description.
 * A detail page that fails to fetch or parse degrades to the card's own data rather than
 * failing the import.
 *
 * Conditional requests are intentionally **not** used: the site answers
 * `Cache-Control: no-store` and sends neither `ETag` nor `Last-Modified`, so there is
 * nothing to revalidate against. [ImportResult.Success] is returned with `null` cache
 * headers (there is no `NotModified` path) and the run relies on idempotent `sourceId`
 * upserts.
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
     * Walks `?page=N` forward from [entryUrl], collecting every card dated today or later.
     *
     * Stops at the first page that contains a past-dated card — the listing is sorted
     * newest-date-first, so that page is the boundary and everything beyond it is archive.
     * Past cards on the boundary page are dropped here rather than at persistence time so
     * the importer does not spend a detail-page fetch on a show that has already happened.
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

            // An empty page means the listing ran out; a past-dated card means this page is
            // the boundary, and the descending order guarantees nothing upcoming follows it.
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
     * Builds the listing URL for [page], leaving page 1 as the configured entry URL so the
     * first request matches what an operator sees in the browser. The query separator adapts
     * to an entry URL that already carries a query string.
     */
    private fun pageUrl(
        entryUrl: String,
        page: Int
    ): String {
        if (page == 1) return entryUrl
        val separator = if ('?' in entryUrl) '&' else '?'
        return "$entryUrl$separator$PAGE_QUERY_PARAM=$page"
    }

    /**
     * Fetches and parses the card's detail page, merging it over the card's data. Any
     * failure — an unreachable page, a redesigned template — degrades to the card alone,
     * which already carries a title, date, type, price and poster.
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
     * Merges a parsed [detail] page over its listing [card].
     *
     * The detail page is the primary source — it owns the untruncated title, the
     * description, the promoter and the ticket link. The card wins on exactly two fields,
     * both because it carries a stronger signal than the detail page's prose rendering
     * (ADR-007 §"Selector Strategy"):
     *  - **date and start time**, from the machine-readable `data-dateStart` attribute; and
     *  - **image**, the original upload path rather than the detail page's cache-keyed
     *    `phpthumbof` thumbnail.
     *
     * Everything else falls back to the card only where the detail page supplied nothing.
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
         * Upper bound on the pagination walk. The venue books roughly a year ahead — nine
         * cards a page put that at well under twenty pages — while its archive runs to 200+,
         * so this is a runaway guard for the case where the listing stops being sorted
         * descending, not an expected limit. Hitting it is logged as a warning.
         */
        private const val MAX_PAGES = 20

        /** pdoPage's page query parameter, as declared in the listing's `pdoPage.initialize({...pageVarKey…})` config. */
        private const val PAGE_QUERY_PARAM = "page"
    }
}

/** Nothing this source withholds needs declaring (#715). */
val URBAN_SPREE_LIMITATIONS = VenueLimitations(EventSource.URBAN_SPREE)
