package de.norm.events.scraper.silentgreen

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for silent green — the Wedding cultural quarter in a 1911 crematorium, whose
 * TYPO3 (`tx_news`) programme is published one calendar month at a time (`/programm` for the current
 * month, `/programm/<yyyy>/<m>` for the rest).
 *
 * Two shapes of the site drive this class, and both are why it implements [EventImporter] directly
 * rather than extending [de.norm.events.scraper.AbstractTwoPageWebsiteImporter]:
 *
 * 1. **The month walk.** The entry URL serves the current month and this importer follows the page's
 *    own next-month link forward. Unlike Matrix, the venue *never* drops that link — it renders an
 *    empty calendar for any month you ask for, arbitrarily far ahead — so the walk stops at the
 *    first month with no entries, the only end-of-programme signal the site gives.
 *    [MAX_MONTH_PAGES] caps it regardless.
 * 2. **Shared detail pages.** A run is listed once per day it is open, so many rows resolve to one
 *    `/programm/detail/<slug>` page — 92 rows over five months for 55 distinct pages, one exhibition
 *    alone accounting for 23. Each distinct page is fetched once and applied to every day of that
 *    run ([SilentGreenEventDetails.applyTo]); a per-event fetch would re-request the same page 20+
 *    times and be serialised by the per-host politeness throttle.
 *
 * A detail page that cannot be fetched or parsed is not fatal: those days keep their calendar data,
 * losing only the doors time, poster and blurb. Conditional requests are intentionally **not** used
 * — the site answers `Cache-Control: private, no-store` with neither validator, and a 304 on the
 * entry page would say nothing about the later months.
 *
 * @see SilentGreenMonthPageScraper for the per-month calendar parsing.
 * @see SilentGreenDetailPageScraper for the run-level detail parsing.
 */
@Component
class SilentGreenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SILENT_GREEN

    private val monthPageScraper = SilentGreenMonthPageScraper()
    private val detailPageScraper = SilentGreenDetailPageScraper()

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
            val monthEvents = monthPageScraper.scrape(document, pageUrl)
            // An empty month ends the programme: the venue keeps offering a next-month link forever.
            if (monthEvents.isEmpty()) break
            events += monthEvents
            pageUrl = nextMonthUrl(document, pageUrl)
        }

        val distinct = events.distinctBy { it.sourceId }
        logger.info { "Scraped ${distinct.size} silent green event(s) across ${visited.size} month page(s) from $url" }
        return ImportResult.Success(events = enrichFromDetailPages(distinct), etag = null, lastModified = null)
    }

    /** Resolves the next-month link — the right-hand arrow of the month switcher — dropping its anchor fragment. */
    private fun nextMonthUrl(
        document: Document,
        pageUrl: String
    ): String? = document.attrAt(".arrow-next a", "href")?.let { resolveUrl(pageUrl, it.substringBefore('#')) }

    /**
     * Fetches each distinct detail page once and applies it to every day of that run.
     *
     * The de-duplication is the point: `sourceUrl` is the run's page, shared by all the days the
     * calendar lists it on.
     */
    private suspend fun enrichFromDetailPages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val detailUrls = events.map { it.sourceUrl }.distinct()
        logger.info { "Fetching ${detailUrls.size} distinct detail page(s) for ${events.size} listed day(s)" }

        val detailsByUrl = detailUrls.associateWith { fetchDetails(it) }
        return events.map { event -> detailsByUrl[event.sourceUrl]?.applyTo(event) ?: event }
    }

    /** Fetches and parses one detail page, degrading to `null` so its days keep the calendar data. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken detail page must not fail the whole import
    private suspend fun fetchDetails(url: String): SilentGreenEventDetails? =
        try {
            detailPageScraper.scrape(htmlFetcher.fetchDocument(url))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch silent green detail page $url, keeping calendar data only" }
            null
        }

    private companion object {
        /**
         * Upper bound on the month pages walked in one run. silent green announces roughly four
         * months ahead, so this is a runaway guard rather than a horizon — it only bites if a month
         * ever renders entries without a working next-month link.
         */
        const val MAX_MONTH_PAGES = 12
    }
}

val SILENT_GREEN_LIMITATIONS =
    VenueLimitations(
        EventSource.SILENT_GREEN,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue names no prices anywhere — an event either links out to a ticket shop or says nothing"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre")
    )
