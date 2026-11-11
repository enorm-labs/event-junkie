package de.norm.events.scraper.barjedervernunft

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.SharedPages
import org.jsoup.nodes.Document

/**
 * Shared importer for the two stages Bar jeder Vernunft runs, Bar jeder Vernunft and TIPI am
 * Kanzleramt. Both sites are one Neos CMS 8.3 build.
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
 * Step 3 is why this class sets [sharedPages] rather than an event-page hook. The venues programme runs, not
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
 * Both venues also publish an iCal feed, the cleaner source — but `robots.txt` disallows
 * `/de/ical/`, so it is not fetched.
 *
 * @see BarJederVernunftOverviewPageScraper for the calendar parsing logic.
 * @see BarJederVernunftShowPageScraper for the show-page parsing logic.
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the two venue importers; an instance of it alone names no venue.
abstract class AbstractBarJederVernunftImporter private constructor(
    htmlFetcher: HtmlFetcher,
    venueName: String,
    final override val eventSource: EventSource,
    private val overviewPageScraper: BarJederVernunftOverviewPageScraper
) : AbstractSinglePageWebsiteImporter(htmlFetcher, venueName, { document, _ -> overviewPageScraper.scrape(document) }) {
    protected constructor(
        htmlFetcher: HtmlFetcher,
        venueName: String,
        eventSource: EventSource
    ) : this(htmlFetcher, venueName, eventSource, BarJederVernunftOverviewPageScraper(eventSource))

    final override val listsWholeProgramme: Boolean = true
    final override val maxListingPages: Int = MAX_BATCHES

    private val showPageScraper = BarJederVernunftShowPageScraper()

    final override val sharedPages: SharedPages<BarJederVernunftShow> =
        SharedPages(
            showPageScraper::scrape,
            BarJederVernunftShow::applyTo,
            // The calendar has no type and a cut teaser; the show page has both (#2505).
            pageOwns = setOf(ScrapedField.EVENT_TYPE, ScrapedField.DESCRIPTION),
            secondLanguage = SecondLanguagePage("en", showPageScraper::description)
        )

    final override fun nextListingPage(
        document: Document,
        url: String
    ): String? = overviewPageScraper.nextBatchUrl(document, url)

    private companion object {
        /** A runaway guard on the batch walk; each calendar ran to four or five batches and an empty one. */
        const val MAX_BATCHES = 12
    }
}
