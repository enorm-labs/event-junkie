package de.norm.events.scraper.tipiamkanzleramt

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
import de.norm.events.scraper.barjedervernunft.BarJederVernunftOverviewPageScraper
import de.norm.events.scraper.barjedervernunft.BarJederVernunftShow
import de.norm.events.scraper.barjedervernunft.BarJederVernunftShowPageScraper
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for TIPI am Kanzleramt, the Tiergarten tent theatre. Bar jeder Vernunft runs it,
 * and both sites are one Neos build, so this class reuses that venue's scrapers unchanged.
 *
 * 1. [HtmlFetcher] fetches `/de/programm/kalender.html` conditionally, then the later batches the
 * page loads on scroll ([BarJederVernunftOverviewPageScraper.nextBatchUrl]): the whole season.
 * 2. The calendar holds one card and one JSON-LD `Event` per performance date.
 * 3. Each distinct show page is read once for genre, prices and the full blurb ([sharedPages]),
 * and its English version for the translation. A run of eight nights is one request, not eight.
 *
 * `robots.txt` disallows `/de/ical/`, so the iCal feed is not read.
 *
 * @see de.norm.events.scraper.barjedervernunft.BarJederVernunftWebsiteImporter for the sister venue.
 * @see <a href="https://www.tipi-am-kanzleramt.de/de/programm/kalender.html">TIPI am Kanzleramt calendar</a>
 */
@Component
class TipiAmKanzleramtWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "TIPI am Kanzleramt", { document, _ -> overviewPageScraper.scrape(document) }) {
    override val eventSource: EventSource = EventSource.TIPI_AM_KANZLERAMT
    override val listsWholeProgramme: Boolean = true
    override val maxListingPages: Int = MAX_BATCHES

    private val showPageScraper = BarJederVernunftShowPageScraper()

    override val sharedPages: SharedPages<BarJederVernunftShow> =
        SharedPages(
            showPageScraper::scrape,
            BarJederVernunftShow::applyTo,
            pageOwns = setOf(ScrapedField.EVENT_TYPE, ScrapedField.DESCRIPTION),
            secondLanguage = SecondLanguagePage("en", showPageScraper::description)
        )

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? = overviewPageScraper.nextBatchUrl(document, url)

    private companion object {
        /** A runaway guard on the batch walk; the season ran to five batches and an empty one. */
        const val MAX_BATCHES = 12

        val overviewPageScraper = BarJederVernunftOverviewPageScraper(EventSource.TIPI_AM_KANZLERAMT)
    }
}

/** German is the house language: the shows, readings and talks are German, the concerts take none. */
val TIPI_AM_KANZLERAMT_LIMITATIONS =
    VenueLimitations(
        EventSource.TIPI_AM_KANZLERAMT,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar and the show pages state one start time and never an Einlass"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself"),
        houseLanguage = SpokenLanguage.GERMAN
    )
