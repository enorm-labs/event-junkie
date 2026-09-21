package de.norm.events.scraper.supamolly

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
 * Website importer for Supamolly Berlin's retro hand-coded PHP programme.
 *
 * The whole programme is one server-rendered page (`?p=programm`, identical to the homepage),
 * so one request per cycle: [HtmlFetcher] fetches it conditionally (ETag / Last-Modified — the
 * server sends neither, so every cycle is a full fetch; the idempotent `sourceId` upsert
 * absorbs that), [SupamollyOverviewPageScraper] parses it.
 *
 * No detail pages: `index.php?programm=<stamp>` serves the full-size flyer JPEG, not HTML, and
 * the advertised `rss.php` feed is unusable (item titles render the date as `"Mi 9.2026..09."`,
 * dropping the day of month).
 *
 * @see SupamollyOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.supamolly.de/?p=programm">Supamolly Berlin</a>
 */
@Component
class SupamollyWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SUPAMOLLY

    private val overviewPageScraper = SupamollyOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Supamolly" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val SUPAMOLLY_LIMITATIONS =
    VenueLimitations(
        EventSource.SUPAMOLLY,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes no prices"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the venue runs no ticket shop")
    )
