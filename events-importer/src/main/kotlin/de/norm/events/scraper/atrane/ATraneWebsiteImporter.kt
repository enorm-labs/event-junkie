package de.norm.events.scraper.atrane

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
 * Website importer for A-Trane, a WordPress site with the EventON calendar.
 *
 * `/programm/` renders every night with its blurb, style tags and ticket prices inline, so one
 * conditional fetch is the whole import ([ATraneProgrammePageScraper]). The page renders one block
 * per month up to eight months ahead, each capped at 35 nights, so it is not declared the whole
 * programme.
 *
 * @see <a href="https://a-trane.de/programm/">A-Trane Programm</a>
 */
@Component
class ATraneWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.A_TRANE

    private val programmePageScraper = ATraneProgrammePageScraper()

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
                val events = programmePageScraper.scrape(fetchResult.document)
                logger.info { "Scraped ${events.size} event(s) from the A-Trane programme" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val A_TRANE_LIMITATIONS =
    VenueLimitations(
        EventSource.A_TRANE,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the end time is a calendar default, 23:50 on most nights"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the site sells one price online and holds reserved seats at that price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "tickets sell in a shop inside the venue's own event card"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the club presents every night itself"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the sold-out line is printed on every night, booked out or not")
    )
