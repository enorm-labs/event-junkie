package de.norm.events.scraper.zurklappe

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
 * Website importer for Zur Klappe's Next.js programme page, a custom site on Vercel.
 *
 * One fetch per import: `/events` carries every upcoming night in its flight payload, parsed by
 * [ZurKlappeOverviewPageScraper]. The detail pages add only a JSON-LD copy of the same fields, so
 * none is fetched. `robots.txt` disallows `/api/`, which the page does not need. Vercel answers
 * `no-store` with no validators, so every import is a full fetch.
 *
 * @see <a href="https://zurklappe.org/events">Zur Klappe events</a>
 */
@Component
class ZurKlappeWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ZUR_KLAPPE

    private val overviewPageScraper = ZurKlappeOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Zur Klappe" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val ZUR_KLAPPE_LIMITATIONS =
    VenueLimitations(
        EventSource.ZUR_KLAPPE,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the site states one title per night and no second line"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes an opening time, not a separate doors time"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no price for a night"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the party name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no cancelled marker for a night"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the venue sets no cover image; its only image is a generated title card for link previews")
    )
