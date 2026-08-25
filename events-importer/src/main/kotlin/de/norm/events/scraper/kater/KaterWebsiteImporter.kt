package de.norm.events.scraper.kater

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
 * Website importer for Kater Berlin's homepage programme.
 *
 * Kater runs WordPress with an `event` post type, but neither of the two structured sources is
 * usable: the REST route (`/wp-json/wp/v2/event`) returns an **empty `acf` object** because the
 * venue does not expose its custom fields to REST, leaving only id, title and permalink; and the
 * `/event/<slug>` pages render nothing but a heading. The homepage carries the entire programme
 * inline instead, so the pipeline is:
 * 1. Fetch the homepage via [HtmlFetcher] with conditional-request support.
 * 2. Parse every `article.event` via [KaterOverviewPageScraper].
 *
 * @see KaterOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.katerclub.de/">Kater Berlin</a>
 */
@Component
class KaterWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.KATER

    private val overviewPageScraper = KaterOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Kater" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val KATER_LIMITATIONS =
    VenueLimitations(
        EventSource.KATER,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club has no category field; only an unambiguous title keyword overrides the party default"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the per-event page carries nothing the homepage listing lacks")
    )
