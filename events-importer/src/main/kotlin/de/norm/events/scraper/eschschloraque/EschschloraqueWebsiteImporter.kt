package de.norm.events.scraper.eschschloraque

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
 * Website importer for Eschschloraque Rümschrümp's Drupal 7 home page.
 *
 * The venue's whole upcoming programme is rendered on the home page as full nodes, so the pipeline
 * is a single fetch:
 * 1. Fetch the home page via [HtmlFetcher] with conditional-request support (ETag / Last-Modified).
 * 2. Parse every `.node-veranstaltung` via [EschschloraqueOverviewPageScraper].
 *
 * The `/kalender/monat` calendar linked from the same navigation is deliberately not crawled. It
 * lists the *current* month — including nights that have already passed — and its `?/YYYY-MM`
 * variants are empty beyond it, so it adds no upcoming event the home page does not already carry.
 * The `/archiv` page holds only past events.
 *
 * @see EschschloraqueOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.eschschloraque.de/">Eschschloraque Rümschrümp</a>
 */
@Component
class EschschloraqueWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ESCHSCHLORAQUE

    private val overviewPageScraper = EschschloraqueOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Eschschloraque" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val ESCHSCHLORAQUE_LIMITATIONS =
    VenueLimitations(
        EventSource.ESCHSCHLORAQUE,
        AcceptedLimitation(LimitedAspect.PRICE, "entry is settled at the door and the venue names no figure"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the venue runs no ticket shop"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue flags nothing sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the venue flags nothing cancelled, so every event stays scheduled"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the programme mixes DJ nights, live sets, bingo and theatre with no kind field anywhere"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes a single ab-HH-Uhr start and never a separate doors time")
    )
