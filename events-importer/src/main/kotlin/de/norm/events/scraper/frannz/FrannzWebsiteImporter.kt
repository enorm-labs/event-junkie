package de.norm.events.scraper.frannz

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
 * Website importer for Frannz Club Berlin's WordPress homepage event listing.
 *
 * All upcoming events render server-side on one page with full details inline (times, prices,
 * promoter, image, description) — no detail-page fetch: [HtmlFetcher] fetches `/`
 * conditionally (ETag / Last-Modified), [FrannzOverviewPageScraper] parses it.
 *
 * @see FrannzOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://frannz.eu/">Frannz Club Berlin</a>
 */
@Component
class FrannzWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.FRANNZ

    private val overviewPageScraper = FrannzOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Frannz" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val FRANNZ_LIMITATIONS =
    VenueLimitations(
        EventSource.FRANNZ,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "nothing on the site links a `/events/<slug>/` page"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the word ausverkauft appears only in the prose blurb, where it also turns up describing a past tour"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "most nights name the ticket seller instead of a figure; only the venue's own party nights carry a structured Abendkasse item, which is read"
        )
    )
