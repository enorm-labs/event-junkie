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
import de.norm.events.scraper.collapseExhibitionRuns
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for silent green, the Wedding cultural quarter in a 1911 crematorium, whose
 * TYPO3 (`tx_news`) programme is published one month at a time. Implements [EventImporter]
 * directly rather than [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] for two reasons.
 * The month walk: the importer follows the page's own next-month link, and unlike Matrix the
 * venue never drops it, rendering an empty calendar arbitrarily far ahead, so the walk stops at
 * the first month with no entries, capped by [MAX_MONTH_PAGES]. Shared detail pages: a run is
 * listed once per open day, 92 rows over five months for 55 distinct pages, one exhibition
 * alone 23, so each page is fetched once and applied to every day ([SilentGreenEventDetails.applyTo]),
 * where a per-event fetch would be serialised by the politeness throttle; an exhibition's days
 * then fold into one event ([collapseExhibitionRuns], ADR-029, #337), a festival's stay apart.
 * A failed detail page is not fatal. Conditional requests are not used: `Cache-Control: private,
 * no-store` with neither validator, and a 304 on the entry page says nothing about later months.
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
        // An exhibition's days share one page, and the page's date block is the run (ADR-029, #337).
        val runs =
            enrichFromDetailPages(distinct).collapseExhibitionRuns { event ->
                "${EventSource.SILENT_GREEN.sourceIdPrefix}${silentGreenDetailSlug(event.sourceUrl)}"
            }
        return ImportResult.Success(events = runs, etag = null, lastModified = null)
    }

    /** Resolves the next-month link — the right-hand arrow of the month switcher — dropping its anchor fragment. */
    private fun nextMonthUrl(
        document: Document,
        pageUrl: String
    ): String? = document.attrAt(".arrow-next a", "href")?.let { resolveUrl(pageUrl, it.substringBefore('#')) }

    /**
     * Fetches each distinct detail page once and applies it to every day of that run; `sourceUrl`
     * is the run's page.
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
         * Upper bound on the month walk: roughly four months announced ahead, so a runaway guard that
         * bites only if a month renders entries without a working next-month link.
         */
        const val MAX_MONTH_PAGES = 12
    }
}

val SILENT_GREEN_LIMITATIONS =
    VenueLimitations(
        EventSource.SILENT_GREEN,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue names no prices anywhere — an event either links out to a ticket shop or says nothing"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue credits itself as the organiser on its own nights, so the stored promoter is the venue")
    )
