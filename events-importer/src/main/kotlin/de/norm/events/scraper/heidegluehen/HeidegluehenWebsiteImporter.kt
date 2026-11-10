package de.norm.events.scraper.heidegluehen

import de.norm.events.common.rethrowIfCancelled
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Heideglühen, the open-air techno party in a former nursery off
 * Beusselstraße.
 *
 * Two pages, no per-event pages:
 * 1. `/monatsvorschau/` — the current month's Saturdays, four or five, replaced wholesale each
 * month. The programme, fetched with conditional-request support.
 * 2. `/aktuell/` — the imminent party only, the one place a DJ lineup appears. Fetched
 * unconditionally after the month page, its lineup applied to the matching date.
 *
 * The second fetch is the [afterListing] pass rather than the per-event detail pattern: one week
 * page for the whole programme, not one per event, and on most days it adds nothing — each
 * lineup is posted a few days ahead. A week page that fails, names another month's date, or has
 * no lineup yet is no error; those events keep the month page's data.
 *
 * @see HeidegluehenMonthPageScraper for the programme parsing.
 * @see HeidegluehenWeekPageScraper for the lineup parsing.
 * @see <a href="https://heidegluehen.berlin/monatsvorschau/">Heideglühen Monatsvorschau</a>
 */
@Component
class HeidegluehenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Heideglühen", HeidegluehenMonthPageScraper()::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.HEIDEGLUEHEN
    override val fetchesBeyondEntryPage: Boolean = true
    override val listedLine: (Int) -> String = { count -> "Scraped $count party date(s) from the Heideglühen month page" }

    private val weekPageScraper = HeidegluehenWeekPageScraper()

    /**
     * Applies the week page's lineup to the party it belongs to. A lineup matching none of the
     * month's dates is dropped rather than guessed: at a month boundary the week page can already
     * show a party the month page no longer lists.
     */
    override suspend fun afterListing(
        events: List<ScrapedEvent>,
        document: Document,
        url: String
    ): List<ScrapedEvent> {
        val lineup = fetchLineup(resolveUrl(url, WEEK_PAGE_PATH)) ?: return events
        logger.info { "Heideglühen published ${lineup.artists.size} DJ(s) for ${lineup.date}" }

        return events.map { event -> if (event.eventDate == lineup.date) lineup.applyTo(event) else event }
    }

    /** Fetches and parses the week page, degrading to `null` so the month's data still imports. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken week page must not fail the whole import
    private suspend fun fetchLineup(url: String): HeidegluehenLineup? =
        try {
            weekPageScraper.scrape(htmlFetcher.fetchDocument(url))
        } catch (e: Exception) {
            rethrowIfCancelled()
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
            "the site has no per-event pages and no archive; one rich-text block lists the month's Saturdays and is replaced wholesale",
            verified = "2026-08-25"
        ),
        AcceptedLimitation(LimitedAspect.PRICE, "the club sells at the door and prints no figure on its programme", verified = "2026-09-30"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-13"),
        houseGenre = "Techno, House"
    )
