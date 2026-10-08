package de.norm.events.scraper.ballhauswedding

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.withEventPageOrFlagged
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Ballhaus Wedding: the `/veranstaltungen` programme, then the Wix Events page of each entry that
 * links one. About a third do; the others link a promoter's shop or nothing, and keep the programme's text.
 *
 * It implements [EventImporter] directly because only some rows have a page of their own. The programme carries no
 * validators that cover the event pages, so every run reads it in full.
 *
 * @see BallhausWeddingOverviewPageScraper for the programme.
 * @see BallhausWeddingEventPageScraper for the event pages.
 */
@Component
class BallhausWeddingWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Dates the year-less programme and drops the past. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}
    private val overviewPageScraper = BallhausWeddingOverviewPageScraper()
    private val eventPageScraper = BallhausWeddingEventPageScraper()

    override val eventSource: EventSource = EventSource.BALLHAUS_WEDDING
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = overviewPageScraper.scrape(htmlFetcher.fetchDocument(url), url, LocalDate.now(clock))
        val events =
            listing.map { event ->
                if (event.sourceUrl == url) {
                    event
                } else {
                    htmlFetcher.withEventPageOrFlagged(event, EVENT_PAGE_OWNS) { document -> eventPageScraper.enrich(event, document) }
                }
            }
        logger.info { "Scraped ${events.size} Ballhaus Wedding event(s), ${events.count { it.sourceUrl != url }} with an event page" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    private companion object {
        val EVENT_PAGE_OWNS = setOf(ScrapedField.DESCRIPTION, ScrapedField.IMAGE, ScrapedField.START_TIME)
    }
}

val BALLHAUS_WEDDING_LIMITATIONS =
    VenueLimitations(
        EventSource.BALLHAUS_WEDDING,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue names no category, so the type comes from words in the title, and a night without one is typed other"
        ),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the programme has no line-up field; only a bare `<name> - Konzert` entry bills its act"),
        AcceptedLimitation(LimitedAspect.IMAGE, "only the entries with a Wix Events page have a poster; the programme has none"),
        AcceptedLimitation(LimitedAspect.GENRE, "nothing on the programme names a style"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme states one house rule, doors 45 minutes before a seated show, and no doors time per event"),
        AcceptedLimitation(LimitedAspect.END_TIME, "only the Wix Events pages state an end")
    )
