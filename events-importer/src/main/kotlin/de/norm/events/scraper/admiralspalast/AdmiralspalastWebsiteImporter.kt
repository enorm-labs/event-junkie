package de.norm.events.scraper.admiralspalast

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Admiralspalast's programme.
 *
 * The venue is on Contao and publishes nothing machine-readable, so it is scraped as HTML — but not
 * with the usual list+detail shape, because **one production page yields many events**. Its
 * `/veranstaltung/<slug>.html` page lists one row per performance, so
 * [AbstractTwoPageWebsiteImporter][de.norm.events.scraper.AbstractTwoPageWebsiteImporter]'s
 * one-overview-entry-to-one-detail-event merge does not fit; [EventImporter] is implemented
 * directly instead. The pipeline is:
 * 1. Fetch the A–Z listing via [HtmlFetcher] with conditional-request support — the discovery list.
 * 2. Walk the `eventkategorie` filter pages to learn each production's category, the only place the
 *    venue states one ([resolveGenres]).
 * 3. Fetch each production page and read its performances via [AdmiralspalastDetailPageScraper].
 *
 * That is one request per production plus one per category. The per-host throttle keeps the walk
 * polite, and a single failed page costs only its own production rather than the import.
 *
 * @see AdmiralspalastListingPageScraper for discovery and the categories.
 * @see AdmiralspalastDetailPageScraper for the performances.
 * @see <a href="https://www.admiralspalast.theater/veranstaltungsuebersicht.html">Admiralspalast programme</a>
 */
@Component
class AdmiralspalastWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ADMIRALSPALAST

    private val listingPageScraper = AdmiralspalastListingPageScraper()
    private val detailPageScraper = AdmiralspalastDetailPageScraper()

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
                val productionUrls = listingPageScraper.scrapeProductionUrls(fetchResult.document, url)
                val genres = resolveGenres(fetchResult.document, url)
                val events = productionUrls.flatMap { scrapeProduction(it, genres[it]) }
                logger.info { "Scraped ${events.size} performance(s) from ${productionUrls.size} Admiralspalast production(s)" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Maps each production URL to the category the venue files it under.
     *
     * The category exists only as a filtered copy of the listing, so each filter page is fetched and
     * every production on it takes that page's label. A production listed under several categories
     * keeps the first — the map is built in the venue's own alphabetical order, so the choice is at
     * least stable between imports. A filter page that fails to load costs only its own category.
     */
    private suspend fun resolveGenres(
        listing: Document,
        listingUrl: String
    ): Map<String, String> {
        val genreUrls = listingPageScraper.scrapeGenreUrls(listing, listingUrl)
        val genres = mutableMapOf<String, String>()

        genreUrls.forEach { (genreUrl, label) ->
            @Suppress("TooGenericExceptionCaught") // Intentional: one unreachable category must not fail the import
            try {
                val document = htmlFetcher.fetchDocument(genreUrl)
                listingPageScraper.scrapeProductionUrls(document, genreUrl).forEach { productionUrl ->
                    genres.putIfAbsent(productionUrl, label)
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load the Admiralspalast category page $genreUrl, its productions stay untyped" }
            }
        }

        logger.info { "Resolved a category for ${genres.size} Admiralspalast production(s) from ${genreUrls.size} filter page(s)" }
        return genres
    }

    /** Fetches one production page and reads its performances; an unreachable page yields none. */
    private suspend fun scrapeProduction(
        productionUrl: String,
        genre: String?
    ): List<ScrapedEvent> =
        @Suppress("TooGenericExceptionCaught") // Intentional: one unreachable production must not fail the import
        try {
            detailPageScraper.scrape(htmlFetcher.fetchDocument(productionUrl), productionUrl, genre)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load the Admiralspalast production page $productionUrl, skipping" }
            emptyList()
        }
}

val ADMIRALSPALAST_LIMITATIONS =
    VenueLimitations(
        EventSource.ADMIRALSPALAST,
        AcceptedLimitation(LimitedAspect.GENRE, "the house classifies by staging format (Konzert, Lesung) and names no musical style anywhere")
    )
