package de.norm.events.scraper.heidegluehen

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Heideglühen, the open-air techno party in a former nursery off
 * Beusselstraße.
 *
 * The venue publishes two pages and no per-event pages at all:
 * 1. `/monatsvorschau/` — the current month's Saturdays, four or five at a time, replaced wholesale
 *    each month. This is the programme, and it is fetched with conditional-request support.
 * 2. `/aktuell/` — the imminent party only, and the one place a DJ lineup ever appears. It is
 *    fetched unconditionally after the month page and its lineup applied to the matching date.
 *
 * The second fetch is why this implements [EventImporter] directly rather than using the per-event
 * detail pattern: there is one week page for the whole programme, not one page per event, and on
 * most days it adds nothing — the venue posts each lineup only a few days ahead. A week page that
 * fails, names another month's date, or has no lineup yet is not an error; those events simply keep
 * the month page's data.
 *
 * @see HeidegluehenMonthPageScraper for the programme parsing.
 * @see HeidegluehenWeekPageScraper for the lineup parsing.
 * @see <a href="https://heidegluehen.berlin/monatsvorschau/">Heideglühen Monatsvorschau</a>
 */
@Component
class HeidegluehenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.HEIDEGLUEHEN

    private val monthPageScraper = HeidegluehenMonthPageScraper()
    private val weekPageScraper = HeidegluehenWeekPageScraper()

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
                val events = monthPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} party date(s) from the Heideglühen month page" }

                ImportResult.Success(
                    events = applyLineup(events, url),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Applies the week page's lineup to the party it belongs to.
     *
     * A lineup that matches none of the month's dates is dropped rather than guessed at: at a
     * month boundary the week page can already show a party the month page no longer lists.
     */
    private suspend fun applyLineup(
        events: List<ScrapedEvent>,
        monthPageUrl: String
    ): List<ScrapedEvent> {
        val lineup = fetchLineup(resolveUrl(monthPageUrl, WEEK_PAGE_PATH)) ?: return events
        logger.info { "Heideglühen published ${lineup.artists.size} DJ(s) for ${lineup.date}" }

        return events.map { event -> if (event.eventDate == lineup.date) lineup.applyTo(event) else event }
    }

    /** Fetches and parses the week page, degrading to `null` so the month's data still imports. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken week page must not fail the whole import
    private suspend fun fetchLineup(url: String): HeidegluehenLineup? =
        try {
            weekPageScraper.scrape(htmlFetcher.fetchDocument(url))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch Heideglühen week page $url, importing the month page alone" }
            null
        }
}

/** The venue's "Diese Woche" page, resolved against the configured month-page URL. */
private const val WEEK_PAGE_PATH = "/aktuell/"

val HEIDEGLUEHEN_LIMITATIONS =
    VenueLimitations(
        EventSource.HEIDEGLUEHEN,
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the site has no per-event pages and no archive; one rich-text block lists the month's Saturdays and is replaced wholesale"
        )
    )
