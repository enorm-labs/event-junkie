package de.norm.events.scraper.wildatheart

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
import java.time.Clock

/**
 * Website importer for Wild at Heart's retro `concerts.php` programme page.
 *
 * The whole concert programme lives on a single hand-coded page with no per-event
 * detail pages, so the pipeline is a single fetch:
 * 1. Fetch `concerts.php` via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified).
 * 2. Parse all events from the page via [WildAtHeartOverviewPageScraper].
 *
 * The configured source URL must point at `concerts.php` (the `wah.htm` frameset entry
 * and its `main.htm` welcome frame carry no event data — only the `topics.htm` nav
 * frame links to the programme).
 *
 * @see WildAtHeartOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.wildatheartberlin.de/concerts.php">Wild at Heart programme</a>
 */
@Component
class WildAtHeartWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's weekday-based year inference. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.WILD_AT_HEART

    private val overviewPageScraper = WildAtHeartOverviewPageScraper(clock)

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
                logger.info { "Scraped ${events.size} event(s) from Wild at Heart" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val WILD_AT_HEART_LIMITATIONS =
    VenueLimitations(
        EventSource.WILD_AT_HEART,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one hand-coded page"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the retro page has no category field; a live-music venue, so an unmarked title defaults to a concert")
    )
