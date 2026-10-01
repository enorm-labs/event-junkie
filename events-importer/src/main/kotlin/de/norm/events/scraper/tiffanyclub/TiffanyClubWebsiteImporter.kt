package de.norm.events.scraper.tiffanyclub

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
import java.time.Clock

/**
 * Website importer for Tiffany Club, a WordPress and Elementor site.
 *
 * `/upcoming-events/` lists the whole programme, months ahead, and supplies every field but the
 * blurb ([TiffanyClubOverviewPageScraper]). Each night's `/event/<slug>/` page adds that blurb
 * ([TiffanyClubDetailPageScraper]); a failed page costs only the description. The WordPress
 * `event` REST type carries the title and nothing else, so the HTML is the source.
 *
 * @see <a href="https://tiffany-berlin.de/upcoming-events/">Tiffany Club upcoming events</a>
 */
@Component
class TiffanyClubWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.TIFFANY_CLUB
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

    private val overviewPageScraper = TiffanyClubOverviewPageScraper(clock)
    private val detailPageScraper = TiffanyClubDetailPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from the Tiffany Club listing" }

                ImportResult.Success(
                    events = events.map { addDescription(it) },
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    @Suppress("TooGenericExceptionCaught") // Intentional: a broken event page must not fail the whole import
    private suspend fun addDescription(event: ScrapedEvent): ScrapedEvent =
        try {
            event.copy(description = detailPageScraper.scrapeDescription(htmlFetcher.fetchDocument(event.sourceUrl)))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch event page for '${event.title}' (${event.sourceUrl}), keeping listing data" }
            event
        }
}

val TIFFANY_CLUB_LIMITATIONS =
    VenueLimitations(
        EventSource.TIFFANY_CLUB,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; the type is read from the title and defaults to a party"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no ticket price, only a guest-list discount inside a form"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the night's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no cancelled marker for a night")
    )
