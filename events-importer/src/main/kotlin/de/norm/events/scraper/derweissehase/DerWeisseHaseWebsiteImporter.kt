package de.norm.events.scraper.derweissehase

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
 * Website importer for Der Weiße Hase's Contao event listing.
 *
 * The club renders its whole upcoming programme on `/events` — one server-rendered block per night,
 * with the date, the start time and the full DJ roster inline. There is no per-event page to follow
 * (the listing links out to Resident Advisor), no pagination, and no JSON or JSON-LD source to prefer
 * over the markup: the page's only `ld+json` graph declares the flyer images and nothing else. Past
 * nights move to a separate `/events-archiv` page, which is not imported. So the pipeline is a single
 * request per cycle:
 * 1. Fetch `/events` via [HtmlFetcher] with conditional-request support (ETag / Last-Modified).
 * 2. Parse every night from that one page via [DerWeisseHaseOverviewPageScraper].
 *
 * The server sends `Cache-Control: no-store` and neither validator header, so the conditional request
 * never actually saves a fetch here. The [FetchResult.NotModified] branch is kept regardless — it
 * costs nothing and covers the server growing an `ETag` later.
 *
 * @see DerWeisseHaseOverviewPageScraper for the HTML parsing logic and the source's limitations.
 * @see <a href="https://derweissehase.club/events">Der Weiße Hase Berlin</a>
 */
@Component
class DerWeisseHaseWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.DER_WEISSE_HASE

    private val overviewPageScraper = DerWeisseHaseOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Der Weiße Hase" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val DER_WEISSE_HASE_LIMITATIONS =
    VenueLimitations(
        EventSource.DER_WEISSE_HASE,
        AcceptedLimitation(LimitedAspect.PRICE, "the club publishes no prices anywhere, not even at the door"),
        AcceptedLimitation(LimitedAspect.GENRE, "the club publishes no genre"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the club publishes no doors time"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the club states no category anywhere and programmes nothing but DJ nights, so the type is fixed rather than inferred"
        ),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the club sells through Resident Advisor and the listing links off-site"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a cancelled night is taken off the page rather than labelled")
    )
