package de.norm.events.scraper.labsaal

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.withEventPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Website importer for the LabSaal, a WordPress site with the Modern Events Calendar plugin. The
 * `/events/` agenda shows about a month, and its JSON-LD adds the UTC offset to each printed
 * time (`19:00` printed, `21:00+02:00` in the block). The plugin's iCalendar feed (`/?mec-ical-feed=1`)
 * carries the whole upcoming programme with the printed wall-clock times, its categories,
 * description and poster, so the importer reads that, then each night's own page for its `Preise`
 * line, which the feed leaves out. A failed page keeps the row as the feed built it. The REST
 * collection `mec-events` has no dates. The configured URL stays the agenda; the feed is resolved
 * against its host.
 *
 * @see LabsaalCalendarScraper for the feed.
 * @see LabsaalEventPageScraper for the event pages.
 * @see <a href="https://labsaal.de/events/">LabSaal Events</a>
 */
@Component
class LabsaalWebsiteImporter(
    private val apiClient: ApiClient,
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}
    private val calendarScraper = LabsaalCalendarScraper()
    private val eventPageScraper = LabsaalEventPageScraper()

    override val eventSource: EventSource = EventSource.LABSAAL
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = calendarScraper.scrape(apiClient.fetchJson(URI(url).resolve(FEED_PATH).toString()))
        val events = withEventPages(htmlFetcher::fetchDocument, listing) { event, document -> eventPageScraper.enrich(event, document) }
        logger.info { "Scraped ${events.size} event(s) from LabSaal" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    private companion object {
        const val FEED_PATH = "/?mec-ical-feed=1"
    }
}

val LABSAAL_LIMITATIONS =
    VenueLimitations(
        EventSource.LABSAAL,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue names no format, so the type comes from words in the title and description, and a night without one is typed other"
        ),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the feed has no line-up field, and a title mixes an act's name with the programme's"),
        AcceptedLimitation(LimitedAspect.GENRE, "the feed names no style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the event page prints price tiers with no label, kept as the note"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "a doors time appears only in the prose of a few nights"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the feed fills an end for every night, also where the site hides it, so no end is taken"),
        AcceptedLimitation(LimitedAspect.END_DATE, "the feed fills an end for every night, also where the site hides it, so no end is taken"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "an outside organiser is named only in the prose"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the feed marks no night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the feed marks no night cancelled or moved")
    )
