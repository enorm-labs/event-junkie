package de.norm.events.scraper.clash

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
 * Website importer for Clash Berlin's WordPress-based event listing.
 *
 * Clash renders all upcoming events inline in the homepage `#events` section — there
 * are no separate detail pages, so the pipeline is a single request:
 * 1. Fetch the homepage via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified).
 * 2. Parse all events from the single page via [ClashOverviewPageScraper].
 *
 * @see ClashOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://clash-berlin.de/">Clash Berlin</a>
 */
@Component
class ClashWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.CLASH

    private val overviewPageScraper = ClashOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Clash" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val CLASH_LIMITATIONS =
    VenueLimitations(
        EventSource.CLASH,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the `event` post type is not exposed over the WordPress REST API and the numeric permalinks 404"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the homepage listing is the whole source and carries no doors time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the homepage listing is the whole source and carries no price"),
        AcceptedLimitation(LimitedAspect.GENRE, "the homepage listing is the whole source and carries no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the homepage listing is the whole source and names no promoter"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site has no category field; the type is inferred from the title, defaulting to a concert")
    )
