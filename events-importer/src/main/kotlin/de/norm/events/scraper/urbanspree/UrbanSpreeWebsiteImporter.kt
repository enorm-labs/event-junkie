package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ListingPage
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.pageNumber
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.urbanspree.UrbanSpreeWebsiteImporter.Companion.MAX_PAGES
import de.norm.events.scraper.walkListingPages
import de.norm.events.scraper.withEventPageOrFlagged
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
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
 * promoter, ticket link or description. A failed or unparseable detail page degrades to the card,
 * flagged so the upsert keeps the stored detail fields (see #2425). Conditional
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
        val today = LocalDate.now(clock)
        val listing =
            walkListingPages(eventSource, htmlFetcher.fetchDocument(url), url, MAX_PAGES, htmlFetcher::fetchDocument) { document, pageUrl ->
                readListingPage(document, pageUrl, url, today)
            }
        val events = listing.items.distinctBy { it.sourceId }.map { enrichFromDetailPage(it) }
        logger.info { "Scraped ${events.size} Urban Spree event(s) across ${listing.pages} listing page(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = listing.complete)
    }

    /**
     * One `?page=N` listing page: its cards dated today or later, and the next page unless this one
     * reaches the past. The listing is newest-first, so everything beyond a past-dated card is
     * archive, and an empty page means it ran out. Past cards are dropped here, sparing a detail fetch.
     */
    private fun readListingPage(
        document: Document,
        pageUrl: String,
        entryUrl: String,
        today: LocalDate
    ): ListingPage<ScrapedEvent> {
        val cards = overviewPageScraper.scrape(document, pageUrl)
        val (upcoming, past) = cards.partition { !it.eventDate.isBefore(today) }
        val page = pageUrl.pageNumber(PAGE_QUERY_PARAM)
        val next = pageUrl(entryUrl, page + 1).takeIf { cards.isNotEmpty() && past.isEmpty() }
        if (next == null) logger.info { "Ending Urban Spree pagination at page $page (${cards.size} card(s), ${past.size} already past)" }
        return ListingPage(upcoming, next)
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
     * The card's detail page merged over the card. A page that yields nothing leaves the card, which
     * carries title, date, type, price and poster, flagged [ScrapedEvent.detailUnavailable].
     */
    private suspend fun enrichFromDetailPage(card: ScrapedEvent): ScrapedEvent =
        htmlFetcher.withEventPageOrFlagged(card, URBAN_SPREE_PAGE_OWNS) { document ->
            detailPageScraper.scrape(document, card.sourceUrl)?.let { merge(detail = it, card = card) }
        }

    /**
     * Merges a parsed [detail] page over its listing [card]. The detail page owns the untruncated
     * title, description, promoter and ticket link. The card wins on date and start time
     * (`data-dateStart`), except for its 23:59 placeholder and for a doors time the page read
     * (#2787). The card also wins on image: the original upload, not the page's cache-keyed
     * `phpthumbof` thumbnail (ADR-007 §"Selector Strategy"). Every other field falls back to the
     * card only where the detail page supplied nothing.
     */
    private fun merge(
        detail: ScrapedEvent,
        card: ScrapedEvent
    ): ScrapedEvent =
        detail.withGapsFrom(card).copy(
            eventDate = card.eventDate,
            startTime =
                when {
                    // The card's 23:59 is a placeholder; the detail page resolves it from the prose when it can (#1904).
                    card.startTime == URBAN_SPREE_LATE_PLACEHOLDER -> detail.startTime ?: card.startTime

                    // The card's time is then the doors time, and the page read the start after it.
                    detail.doorsTime != null -> detail.startTime

                    else -> card.startTime ?: detail.startTime
                },
            imageUrl = card.imageUrl ?: detail.imageUrl
        )

    private companion object {
        /** The card cuts the title, drops the support note and misses the club-night type; the page has all three (#2505). */
        val URBAN_SPREE_PAGE_OWNS = setOf(ScrapedField.TITLE, ScrapedField.SUBTITLE, ScrapedField.EVENT_TYPE)

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
