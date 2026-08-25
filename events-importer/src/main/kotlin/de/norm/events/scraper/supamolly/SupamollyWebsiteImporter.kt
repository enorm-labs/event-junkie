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
 * The venue's whole programme is one server-rendered page (`?p=programm`, identical
 * to the homepage), so the pipeline is a single request per cycle:
 * 1. Fetch the programme page via [HtmlFetcher] with conditional request support
 *    (ETag / Last-Modified — the server currently sends neither, so every cycle is a
 *    full fetch; the idempotent `sourceId` upsert absorbs that).
 * 2. Parse all events from that page via [SupamollyOverviewPageScraper].
 *
 * There are no detail pages to follow: `index.php?programm=<stamp>` serves the
 * full-size flyer JPEG rather than HTML, and the advertised `rss.php` feed is
 * unusable (its item titles render the date as `"Mi 9.2026..09."`, dropping the day of month).
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
