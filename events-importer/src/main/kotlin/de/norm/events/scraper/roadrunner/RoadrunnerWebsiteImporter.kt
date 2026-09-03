package de.norm.events.scraper.roadrunner

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
import java.time.Clock

/**
 * Website importer for Roadrunner's Paradise' retro `programm.html` page.
 *
 * The whole programme lives on a single hand-coded HTML page with no per-event
 * detail pages, so the pipeline is a single fetch:
 * 1. Fetch `programm.html` via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified).
 * 2. Parse all events from the page via [RoadrunnerOverviewPageScraper].
 *
 * The configured source URL must point at `programm.html` (the homepage is a
 * separate landing page that carries no event data).
 *
 * @see RoadrunnerOverviewPageScraper for the HTML parsing logic.
 * @see <a href="http://www.roadrunners-paradise.de/programm.html">Roadrunner's Paradise programme</a>
 */
@Component
class RoadrunnerWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's past-event cutoff and year inference. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ROADRUNNER

    private val overviewPageScraper = RoadrunnerOverviewPageScraper(clock)

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
                logger.info { "Scraped ${events.size} event(s) from Roadrunner's Paradise" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val ROADRUNNER_LIMITATIONS =
    VenueLimitations(
        EventSource.ROADRUNNER,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme lives on one hand-coded page"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the retro programme carries no category field; a live-music venue, so an unmarked title defaults to a concert"
        )
    )
