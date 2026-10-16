package de.norm.events.scraper.delphi

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
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
 * Step 3 is why this class sets [sharedPages] rather than an event-page hook, as at Bar jeder Vernunft. The
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
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Theater im Delphi", DelphiProgrammePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.THEATER_IM_DELPHI
    override val listsWholeProgramme: Boolean = true

    private val productionPageScraper = DelphiProductionPageScraper()

    override val sharedPages: SharedPages<DelphiProduction> =
        SharedPages(
            productionPageScraper::scrape,
            DelphiProduction::applyTo,
            pageOwns = setOf(ScrapedField.IMAGE, ScrapedField.DESCRIPTION),
            secondLanguage = SecondLanguagePage("en") { productionPageScraper.scrape(it)?.description }
        )
}

val THEATER_IM_DELPHI_LIMITATIONS =
    VenueLimitations(
        EventSource.THEATER_IM_DELPHI,
        AcceptedLimitation(LimitedAspect.GENRE, "the labels are formats; only Kammermusik and Elektronische Musik name a style"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself")
    )
