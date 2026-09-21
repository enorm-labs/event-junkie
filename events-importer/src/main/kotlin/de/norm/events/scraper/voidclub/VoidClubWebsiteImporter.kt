package de.norm.events.scraper.voidclub

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for VOID Club's homepage programme.
 *
 * Hand-coded Bootstrap on plain Apache — no CMS, REST API, `sitemap.xml`, feed or structured
 * data (not even a `robots.txt`), so the homepage, the whole programme inline with every
 * lineup, is the source: [HtmlFetcher] fetches it conditionally, [VoidClubOverviewPageScraper]
 * parses every `article.void-event-card`.
 *
 * @see VoidClubOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.void-club.de/">VOID Club Berlin</a>
 */
@Component
class VoidClubWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.VOID_CLUB

    private val overviewPageScraper = VoidClubOverviewPageScraper()

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
                val events = overviewPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from VOID Club" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val VOID_CLUB_LIMITATIONS =
    VenueLimitations(
        EventSource.VOID_CLUB,
        AcceptedLimitation(LimitedAspect.START_TIME, "the venue publishes no times; every night stores a bare date"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes no times; every night stores a bare date"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes no prices"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the venue publishes no per-event text"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every night points at the programme page"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the club states no category; `.void-event-genre` names the music and `.void-event-venue` the rooms in use, neither of which is a kind of event"
        )
    )
