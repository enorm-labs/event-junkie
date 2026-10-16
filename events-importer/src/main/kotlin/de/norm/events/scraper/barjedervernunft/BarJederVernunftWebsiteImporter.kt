package de.norm.events.scraper.barjedervernunft

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Bar jeder Vernunft, the Wilmersdorf Spiegelzelt (cabaret / variety
 * theatre), on Neos CMS 8.3.
 *
 * Overview → show page, but **not** the per-event detail fetch
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] performs:
 * 1. [HtmlFetcher] fetches `/de/programm/kalender.html` conditionally (ETag / Last-Modified).
 * The page holds the first batch of dates, and the later batches load as the visitor scrolls,
 * so they are fetched too ([BarJederVernunftOverviewPageScraper.nextBatchUrl]).
 * 2. [BarJederVernunftOverviewPageScraper] parses one event per performance date.
 * 3. Each **distinct** `/programmuebersicht/<show>.html` page once, applying genre, prices and
 * description to every date of that show ([BarJederVernunftShow.applyTo]).
 *
 * Step 3 is why this class sets [sharedPages] rather than an event-page hook. The venue programmes runs, not
 * one-off gigs, so one production owns most of the calendar — at the time of writing 28
 * calendar cards resolve to 2 show pages. Per-event fetching would re-request the same page
 * 20+ times per import, which the per-host politeness throttle would (rightly) serialise into
 * a slow, pointless crawl.
 *
 * Each show page links its English version, which translates the blurb: one more request per show.
 *
 * A show page that cannot be fetched or parsed is not fatal: those dates keep the calendar data
 * and are flagged, so the upsert keeps the genre, price and blurb stored last time.
 *
 * The venue also publishes an iCal feed, the cleaner source — but `robots.txt` disallows
 * `/de/ical/`, so it is not fetched.
 *
 * @see BarJederVernunftOverviewPageScraper for the calendar parsing logic.
 * @see BarJederVernunftShowPageScraper for the show-page parsing logic.
 * @see <a href="https://www.bar-jeder-vernunft.de/de/programm/kalender.html">Bar jeder Vernunft calendar</a>
 */
@Component
class BarJederVernunftWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Bar jeder Vernunft", { document, _ -> overviewPageScraper.scrape(document) }) {
    override val eventSource: EventSource = EventSource.BAR_JEDER_VERNUNFT
    override val listsWholeProgramme: Boolean = true
    override val maxListingPages: Int = MAX_BATCHES

    private val showPageScraper = BarJederVernunftShowPageScraper()

    override val sharedPages: SharedPages<BarJederVernunftShow> =
        SharedPages(
            showPageScraper::scrape,
            BarJederVernunftShow::applyTo,
            // The calendar has no type and a cut teaser; the show page has both (#2505).
            pageOwns = setOf(ScrapedField.EVENT_TYPE, ScrapedField.DESCRIPTION),
            secondLanguage = SecondLanguagePage("en", showPageScraper::description)
        )

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? = overviewPageScraper.nextBatchUrl(document, url)

    private companion object {
        /** A runaway guard on the batch walk; the calendar ran to four batches and an empty one. */
        const val MAX_BATCHES = 12

        val overviewPageScraper = BarJederVernunftOverviewPageScraper()
    }
}

/**
 * German is the house language because the programme is German in practice (#2584). On `2026-10-04` the
 * production API listed 46 upcoming shows and comedy nights, and all 46 descriptions were German. The
 * concerts take no language.
 */
val BAR_JEDER_VERNUNFT_LIMITATIONS =
    VenueLimitations(
        EventSource.BAR_JEDER_VERNUNFT,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar and the show pages state one Beginn time and never an Einlass"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself"),
        houseLanguage = SpokenLanguage.GERMAN
    )
