package de.norm.events.scraper.barjedervernunft

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Bar jeder Vernunft, the Wilmersdorf Spiegelzelt (cabaret / variety
 * theatre), on Neos CMS 8.3.
 *
 * Overview → show page, but **not** the per-event detail fetch
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] performs:
 * 1. [HtmlFetcher] fetches `/de/programm/kalender.html` conditionally (ETag / Last-Modified).
 * 2. [BarJederVernunftOverviewPageScraper] parses one event per performance date.
 * 3. Each **distinct** `/programmuebersicht/<show>.html` page once, applying genre, prices and
 * description to every date of that show ([BarJederVernunftShow.applyTo]).
 *
 * Step 3 is why this class implements [EventImporter] directly. The venue programmes runs, not
 * one-off gigs, so one production owns most of the calendar — at the time of writing 28
 * calendar cards resolve to 2 show pages. Per-event fetching would re-request the same page
 * 20+ times per import, which the per-host politeness throttle would (rightly) serialise into
 * a slow, pointless crawl.
 *
 * A show page that cannot be fetched or parsed is not fatal: those dates keep the calendar
 * data, losing only genre, price and full blurb.
 *
 * The venue also publishes an iCal feed, the cleaner source — but `robots.txt` disallows
 * `/de/ical/`, so it is not fetched.
 *
 * @see BarJederVernunftOverviewPageScraper for the calendar parsing logic.
 * @see BarJederVernunftShowPageScraper for the show-page parsing logic.
 * @see <a href="https://www.bar-jeder-vernunft.de/de/programm/kalender.html">Bar jeder Vernunft calendar</a>
 */
@Component
class BarJederVernunftWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.BAR_JEDER_VERNUNFT

    private val overviewPageScraper = BarJederVernunftOverviewPageScraper()
    private val showPageScraper = BarJederVernunftShowPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = overviewPageScraper.scrape(fetchResult.document)
                logger.info { "Scraped ${events.size} performance date(s) from Bar jeder Vernunft" }

                ImportResult.Success(
                    events = enrichFromShowPages(events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Fetches each distinct show page once and applies it to every date of that show. The
     * de-duplication is the point: `sourceUrl` is the show's page, shared by all its dates.
     */
    private suspend fun enrichFromShowPages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val showUrls = events.map { it.sourceUrl }.distinct()
        logger.info { "Fetching ${showUrls.size} distinct show page(s) for ${events.size} performance date(s)" }

        val showsByUrl = showUrls.associateWith { fetchShow(it) }
        return events.map { event -> showsByUrl[event.sourceUrl]?.applyTo(event) ?: event }
    }

    /** Fetches and parses one show page, degrading to `null` so its dates keep the calendar data. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken show page must not fail the whole import
    private suspend fun fetchShow(url: String): BarJederVernunftShow? =
        try {
            showPageScraper.scrape(htmlFetcher.fetchDocument(url))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch show page $url, keeping calendar data only" }
            null
        }
}

val BAR_JEDER_VERNUNFT_LIMITATIONS =
    VenueLimitations(
        EventSource.BAR_JEDER_VERNUNFT,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar and the show pages state one Beginn time and never an Einlass")
    )
