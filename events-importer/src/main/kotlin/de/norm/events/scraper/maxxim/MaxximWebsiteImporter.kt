package de.norm.events.scraper.maxxim

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
 * Website importer for MAXXIM's Wix Events programme.
 *
 * The whole programme — including prices and sold-out flags — is carried by the
 * `wix-warmup-data` payload of the single `/partys` page, so the pipeline is one
 * HTTP request per import cycle:
 * 1. Fetch `/partys` via [HtmlFetcher] with conditional request support
 *    (Wix serves a weak `ETag`).
 * 2. Parse every night out of the embedded JSON via [MaxximOverviewPageScraper].
 *
 * No `/event-details/<slug>` page is fetched — unlike Loge, whose price only
 * appears there.
 *
 * The widget ships the upcoming window only (~18 nights) and reports `hasMore: true`; loading the
 * rest needs the authenticated widget API, whose `_api/wix-one-events-server/…` paths 404 for an
 * anonymous client. The 1,700-entry `event-pages-sitemap.xml` is overwhelmingly the archive, so it
 * is deliberately not crawled as a workaround — a small imported count here is the venue's window,
 * not a truncated import.
 *
 * @see MaxximOverviewPageScraper for the parsing logic.
 * @see <a href="https://www.maxxim-berlin.de/partys">MAXXIM programme</a>
 */
@Component
class MaxximWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MAXXIM

    private val overviewPageScraper = MaxximOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from MAXXIM" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val MAXXIM_LIMITATIONS =
    VenueLimitations(
        EventSource.MAXXIM,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club publishes no categories; every night is a DJ dance party")
    )
