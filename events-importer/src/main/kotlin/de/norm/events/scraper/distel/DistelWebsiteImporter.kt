package de.norm.events.scraper.distel

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.scrapeListingPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Kabarett-Theater DISTEL at Friedrichstraße, a Contao site.
 *
 * `/spielplan/kalender/` shows the current month; its navigation links the next as `?month=YYYYMM`,
 * and every month links a next one, so the walk stops at the first month with no performance:
 * about five months today. Each show page is then read once, for its text and photo, and applied
 * to every performance of the show; a failed show page leaves its performances with the calendar's
 * fields.
 *
 * A calendar page weighs about 7 MB, almost all of it an animated SVG ticket icon inlined per
 * performance. The ticket shop has no listing to read instead, and its `robots.txt` disallows it.
 *
 * @see DistelCalendarPageScraper for the calendar parsing.
 * @see DistelShowPageScraper for the show page.
 * @see <a href="https://distel-berlin.de/spielplan/kalender/">DISTEL Kalender</a>
 */
@Component
class DistelWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.DISTEL
    override val fetchesBeyondEntryPage: Boolean = true

    private val calendarScraper = DistelCalendarPageScraper()
    private val showScraper = DistelShowPageScraper()

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
                val listing =
                    htmlFetcher.scrapeListingPages(
                        eventSource,
                        fetchResult.document,
                        url,
                        MAX_MONTHS,
                        { document, _ -> nextMonth(document) },
                        calendarScraper::scrape
                    )
                logger.info { "Scraped ${listing.events.size} DISTEL performance(s) from the calendar" }

                ImportResult.Success(
                    events = enrichFromShowPages(listing.events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified,
                    complete = listing.complete
                )
            }
        }

    /** The navigation's latest `?month=` link, or null once a month lists nothing. */
    private fun nextMonth(document: Document): String? =
        if (calendarScraper.isEmptyMonth(document)) {
            null
        } else {
            document
                .select("a[href*=kalender/?month=]")
                .maxByOrNull { it.attr("href").substringAfter("month=").take(MONTH_DIGITS) }
                ?.absUrl("href")
                ?.substringBefore('#')
        }

    private suspend fun enrichFromShowPages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val shows = events.map { it.sourceUrl }.distinct().associateWith { fetchShow(it) }
        return events.map { event ->
            shows[event.sourceUrl]?.let { show -> event.copy(description = show.description, imageUrl = show.imageUrl) } ?: event
        }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: a failed show page keeps the calendar's fields
    private suspend fun fetchShow(url: String): DistelShow? =
        try {
            showScraper.scrape(htmlFetcher.fetchDocument(url))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch DISTEL show page $url, keeping the calendar's fields" }
            null
        }

    private companion object {
        /** A runaway guard; the calendar runs about five months ahead. */
        const val MAX_MONTHS = 12

        const val MONTH_DIGITS = 6
    }
}

val DISTEL_LIMITATIONS =
    VenueLimitations(
        EventSource.DISTEL,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar states one time per performance"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices appear only inside the ticket shop, which robots.txt closes"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the ensemble's shows name a cast only in prose"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the calendar names no format; ensemble Kabarett, guest shows and a talk series share one list"),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the theatre presents every performance itself")
    )
