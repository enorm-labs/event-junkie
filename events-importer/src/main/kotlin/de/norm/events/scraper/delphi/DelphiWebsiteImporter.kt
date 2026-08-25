package de.norm.events.scraper.delphi

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Theater im Delphi, the 1929 silent-cinema building in Weißensee now run as a
 * theatre and concert hall.
 *
 * The pipeline is programme → production page, but **not** the per-event detail fetch
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] performs:
 * 1. Fetch `/programm/` via [HtmlFetcher] with conditional-request support (ETag / Last-Modified).
 * 2. Parse one event per performance date via [DelphiProgrammePageScraper].
 * 3. Fetch each **distinct** `?prod=<id>` page once and apply its full blurb and photo to every
 *    date of that production ([DelphiProduction.applyTo]).
 *
 * Step 3 is why this class implements [EventImporter] directly, as at Bar jeder Vernunft. The house
 * programmes runs, not one-off nights: at the time of writing 24 performance rows resolve to 15
 * production pages, and one ballet alone owns 8 of them. Fetching per event would re-request the
 * same page eight times, which the per-host politeness throttle would rightly serialise into a
 * slow, pointless crawl.
 *
 * A production page that cannot be fetched or parsed is not fatal: those dates keep the programme
 * row's teaser and thumbnail, losing only the fuller text and photo.
 *
 * @see DelphiProgrammePageScraper for the programme parsing logic.
 * @see DelphiProductionPageScraper for the production-page parsing logic.
 * @see <a href="https://theater-im-delphi.de/programm/">Theater im Delphi programme</a>
 */
@Component
class DelphiWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.THEATER_IM_DELPHI

    private val programmePageScraper = DelphiProgrammePageScraper()
    private val productionPageScraper = DelphiProductionPageScraper()

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
                val events = programmePageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} performance date(s) from Theater im Delphi" }

                ImportResult.Success(
                    events = enrichFromProductionPages(events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Fetches each distinct production page once and applies it to every date of that production.
     *
     * The de-duplication is the point: `sourceUrl` is the production's page, shared by all of its
     * performance dates.
     */
    private suspend fun enrichFromProductionPages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val productionUrls = events.map { it.sourceUrl }.distinct()
        logger.info { "Fetching ${productionUrls.size} distinct production page(s) for ${events.size} performance date(s)" }

        val productionsByUrl = productionUrls.associateWith { fetchProduction(it) }
        return events.map { event -> productionsByUrl[event.sourceUrl]?.applyTo(event) ?: event }
    }

    /** Fetches and parses one production page, degrading to `null` so its dates keep the row data. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken production page must not fail the whole import
    private suspend fun fetchProduction(url: String): DelphiProduction? =
        try {
            productionPageScraper.scrape(htmlFetcher.fetchDocument(url))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch production page $url, keeping programme-row data only" }
            null
        }
}

/** Nothing this source withholds needs declaring (#715). */
val THEATER_IM_DELPHI_LIMITATIONS = VenueLimitations(EventSource.THEATER_IM_DELPHI)
