package de.norm.events.scraper.zitaclub

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.withEventPageOrFlagged
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Website importer for the Zita Club & Bar, a WordPress site with the Events Manager plugin. It
 * reads the plugin's `/events.ics` feed, every upcoming night in one response, then each night's
 * own page for its tags, ticket link and price, which the feed leaves out. The query string
 * matters: the bare feed is served from the WP-Optimize page cache, and a request with parameters
 * bypasses it. The configured URL stays the home page, the venue's real entry point.
 *
 * @see ZitaClubCalendarScraper for the feed.
 * @see ZitaClubEventPageScraper for the event pages.
 */
@Component
class ZitaClubWebsiteImporter(
    private val apiClient: ApiClient,
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}
    private val calendarScraper = ZitaClubCalendarScraper()
    private val eventPageScraper = ZitaClubEventPageScraper()

    override val eventSource: EventSource = EventSource.ZITA_CLUB
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = calendarScraper.scrape(apiClient.fetchJson(URI(url).resolve(FEED_PATH).toString()))
        val events = listing.map { event -> htmlFetcher.withEventPageOrFlagged(event) { document -> eventPageScraper.enrich(event, document) } }
        logger.info { "Scraped ${events.size} event(s) from Zita Club" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    private companion object {
        const val FEED_PATH = "/events.ics?scope=future"
    }
}

val ZITA_CLUB_LIMITATIONS =
    VenueLimitations(
        EventSource.ZITA_CLUB,
        AcceptedLimitation(LimitedAspect.ARTISTS, "the DJs appear only in the prose, and a house night names none"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes one time per night"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category, so every night is a party unless its title says otherwise"),
        AcceptedLimitation(LimitedAspect.GENRE, "a night whose page carries no tags names no style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the price field is left empty on some nights"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "an outside promoter is named only in the prose"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site marks no night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site marks no night cancelled or moved")
    )
