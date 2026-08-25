package de.norm.events.scraper.maaya

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
 * Website importer for MAAYA Berlin, the Afro-diasporic cultural venue and open-air pool on the
 * RAW-Gelände.
 *
 * The venue's whole published programme is the **NEXT DATES** section of its WordPress home page,
 * with no per-event pages, so the pipeline is a single request per cycle:
 * 1. Fetch the home page via [HtmlFetcher] with conditional-request support (ETag / Last-Modified).
 * 2. Parse the NEXT DATES section via [MaayaOverviewPageScraper].
 *
 * Cloudflare fronts the site and answers some non-browser clients with a 403 challenge page — curl
 * is blocked outright — but it serves the JVM HTTP stack this importer runs on normally. A sudden
 * run of `HTTP 403` failures on this source therefore means the bot rules tightened, not that the
 * page moved.
 *
 * The listing is short by nature: it runs about two weeks ahead, so an import returns a dozen or so
 * events. That is the venue's real horizon rather than a parsing limit — it announces the rest
 * through its newsletter and Instagram, as the section's own footnote says.
 *
 * @see MaayaOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://maaya.de/">MAAYA Berlin</a>
 */
@Component
class MaayaWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MAAYA

    private val overviewPageScraper = MaayaOverviewPageScraper()

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
                logger.info { "Scraped ${events.size} event(s) from MAAYA" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val MAAYA_LIMITATIONS =
    VenueLimitations(
        EventSource.MAAYA,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the programme is one hand-built section of the WordPress home page"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the programme is one hand-built section of the home page and carries no detail text"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes an entry note in words and no numeric price"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "there is no lineup field, and the titles are series and party names rather than acts"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes no doors time"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre")
    )
