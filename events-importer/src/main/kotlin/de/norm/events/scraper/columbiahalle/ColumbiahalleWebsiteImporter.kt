package de.norm.events.scraper.columbiahalle

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
 * Website importer for Columbiahalle Berlin's Contao event listing.
 *
 * Columbiahalle renders its whole upcoming programme on `/veranstaltungen.html` with every field
 * expanded inline — times, prices, promoter, ticket link, poster and the untruncated blurb — so no
 * detail-page fetching is needed. The cards' "Kalender-Eintrag" links look like detail pages
 * (`veranstaltung/<alias>.html`) but serve an **iCal download** carrying strictly less than the
 * listing, so they are deliberately not followed. The pipeline is:
 * 1. Fetch `/veranstaltungen.html` via [HtmlFetcher] with conditional-request support (the site
 *    sends neither ETag nor Last-Modified, so in practice every run is a full fetch of one page).
 * 2. Parse all events from that single page via [ColumbiahalleOverviewPageScraper].
 *
 * @see ColumbiahalleOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.columbiahalle.berlin/veranstaltungen.html">Columbiahalle Berlin</a>
 */
@Component
class ColumbiahalleWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.COLUMBIAHALLE

    private val overviewPageScraper = ColumbiahalleOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Columbiahalle" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val COLUMBIAHALLE_LIMITATIONS =
    VenueLimitations(
        EventSource.COLUMBIAHALLE,
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the venue's own iCal export keys the event on the same Contao id and points back at the listing anchor"
        )
    )
