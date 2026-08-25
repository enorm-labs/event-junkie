package de.norm.events.scraper.saalchen

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
 * Website importer for Säälchen's programme on the Holzmarkt site's shared calendar.
 *
 * The whole programme sits on one `/kalender` page — its month tabs are in-page anchors, not
 * extra requests — and every field the model wants is already on it, so the pipeline is a single
 * fetch:
 * 1. Fetch `/kalender` via [HtmlFetcher] with conditional-request support (ETag / Last-Modified).
 * 2. Filter the calendar to the venue and parse it via [SaalchenOverviewPageScraper].
 *
 * The `/veranstaltung/<slug>` detail pages are deliberately not fetched: the listing's embedded
 * AddToCalendar payload already carries the event's own prose, times and price.
 *
 * @see SaalchenOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.holzmarkt.com/kalender">Holzmarkt calendar</a>
 */
@Component
class SaalchenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SAALCHEN

    private val overviewPageScraper = SaalchenOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Säälchen" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val SAALCHEN_LIMITATIONS =
    VenueLimitations(
        EventSource.SAALCHEN,
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre field of its own")
    )
