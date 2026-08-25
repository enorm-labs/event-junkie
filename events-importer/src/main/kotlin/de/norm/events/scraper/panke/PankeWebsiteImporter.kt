package de.norm.events.scraper.panke

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
 * Website importer for Panke Culture, the club, café and gallery on the Panke in Wedding.
 *
 * The venue renders its whole programme on one WordPress page with no per-event pages, so the
 * pipeline is a single request per cycle:
 * 1. Fetch `/programme/` via [HtmlFetcher] with conditional-request support (ETag / Last-Modified).
 * 2. Parse the page's **UPCOMING EVENTS** list via [PankeProgrammePageScraper].
 *
 * The venue published nothing scrapable until 2026 — its programme lived on social media and in a
 * newsletter, which is why it sat in the Blocked list until the 3 August 2026 re-check.
 *
 * @see PankeProgrammePageScraper for the HTML parsing logic.
 * @see <a href="https://www.pankeculture.com/programme/">Panke Culture programme</a>
 */
@Component
class PankeWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.PANKE

    private val programmePageScraper = PankeProgrammePageScraper()

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
                val events = programmePageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from Panke Culture" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val PANKE_LIMITATIONS =
    VenueLimitations(
        EventSource.PANKE,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the venue expands each event's full text inline and publishes no page per event"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue publishes no category, and its titles are series names rather than formats")
    )
