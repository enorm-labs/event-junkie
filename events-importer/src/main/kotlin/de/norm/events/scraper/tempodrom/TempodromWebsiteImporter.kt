package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Tempodrom's programme.
 *
 * The venue's `/programm-und-tickets/` page embeds its whole programme as schema.org `Event`
 * JSON-LD, so this is an HTML fetch whose *payload* is structured data — the markup is never
 * selected against, and no detail page is needed. The pipeline is:
 * 1. Fetch the listing via [HtmlFetcher] with conditional-request support; the server sends
 *    `Last-Modified`, so an unchanged programme costs one 304.
 * 2. Parse the JSON-LD via [TempodromOverviewPageScraper].
 *
 * @see TempodromOverviewPageScraper for the JSON-LD parsing logic.
 * @see <a href="https://www.tempodrom.de/programm-und-tickets/">Tempodrom programme</a>
 */
@Component
class TempodromWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.TEMPODROM

    private val overviewPageScraper = TempodromOverviewPageScraper()

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
                val events = overviewPageScraper.scrape(fetchResult.document)
                logger.info { "Scraped ${events.size} event(s) from Tempodrom" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

/** Nothing this source withholds needs declaring (#715). */
val TEMPODROM_LIMITATIONS = VenueLimitations(EventSource.TEMPODROM)
