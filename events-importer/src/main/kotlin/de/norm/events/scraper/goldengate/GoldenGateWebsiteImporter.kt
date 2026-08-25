package de.norm.events.scraper.goldengate

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
 * Website importer for Golden Gate Berlin's Elementor-built WordPress homepage.
 *
 * Golden Gate announces only the current Thursday–Saturday block, inline on its homepage — three
 * nights, each a date line, a title and a DJ roster. There are no per-event pages, no custom
 * `event` post type in the WordPress REST API (only the stock types are registered), and no
 * structured data, so the single rendered page is the source. The pipeline is:
 * 1. Fetch the homepage via [HtmlFetcher] with conditional-request support.
 * 2. Parse the announced nights via [GoldenGateOverviewPageScraper].
 *
 * Past nights stay on the page until the block rolls over and are dropped centrally at persistence
 * time by [EventUpsertService][de.norm.events.scraper.EventUpsertService], so a run late in the week
 * legitimately stores fewer events than the page shows — as few as one.
 *
 * @see GoldenGateOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://goldengate-berlin.de/">Golden Gate Berlin</a>
 */
@Component
class GoldenGateWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.GOLDEN_GATE

    private val overviewPageScraper = GoldenGateOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from Golden Gate" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val GOLDEN_GATE_LIMITATIONS =
    VenueLimitations(
        EventSource.GOLDEN_GATE,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the club emits no category at all and programmes nothing but DJ nights, so the type is fixed rather than inferred"
        ),
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "there is no custom `event` post type in the WordPress REST API and no structured data; the single rendered page is the source"
        )
    )
