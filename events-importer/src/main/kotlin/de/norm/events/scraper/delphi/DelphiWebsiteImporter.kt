package de.norm.events.scraper.delphi

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.enrichFromSharedPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Theater im Delphi, the 1929 silent-cinema building in Weißensee now run
 * as a theatre and concert hall.
 *
 * Programme → production page, but **not** the per-event detail fetch
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] performs:
 * 1. [HtmlFetcher] fetches `/programm/` conditionally (ETag / Last-Modified).
 * 2. [DelphiProgrammePageScraper] parses one event per performance date.
 * 3. Each **distinct** `?prod=<id>` page once, applying its full blurb and photo to every date
 * of that production ([DelphiProduction.applyTo]).
 *
 * Step 3 is why this class implements [EventImporter] directly, as at Bar jeder Vernunft. The
 * house programmes runs, not one-off nights: at the time of writing 24 performance rows resolve
 * to 15 production pages, one ballet alone owning 8. Per-event fetching would re-request the
 * same page eight times, which the per-host politeness throttle would rightly serialise into a
 * slow, pointless crawl.
 *
 * Each production page links its English version (`/en/programm/?prod=<id>`, `hreflang="en"`),
 * which translates the blurb, so that page is read too for the second language: one more request
 * per production, not per date.
 *
 * A production page that cannot be fetched or parsed is not fatal: its dates keep the row's teaser
 * and are flagged, so the upsert keeps the text and photo the page stored last time.
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
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

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
                    events =
                        htmlFetcher.enrichFromSharedPages(
                            events,
                            productionPageScraper::scrape,
                            DelphiProduction::applyTo,
                            pageOwns = setOf(ScrapedField.IMAGE, ScrapedField.DESCRIPTION),
                            secondLanguage = SecondLanguagePage("en") { productionPageScraper.scrape(it)?.description }
                        ),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

/** Nothing this source withholds needs declaring (#715). */
val THEATER_IM_DELPHI_LIMITATIONS = VenueLimitations(EventSource.THEATER_IM_DELPHI)
