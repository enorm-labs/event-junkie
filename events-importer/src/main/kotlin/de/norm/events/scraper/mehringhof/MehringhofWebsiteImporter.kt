package de.norm.events.scraper.mehringhof

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Mehringhof-Theater in Kreuzberg: the month tables on its IONOS site,
 * then each performance's ticket page for text, image, price and availability.
 *
 * `/programm/` is the current month; its menu links the following months' pages. Each page links
 * the others but not itself, so the walk follows the first menu link it has not read: October to
 * November to December, where the only link left points back. The theatre lists later months in
 * `robots.txt` as disallowed until it opens them.
 *
 * The listing keeps the title, the act and the date; the ticket page supplies the rest.
 *
 * @see MehringhofProgrammPageScraper for the month table.
 * @see MehringhofTicketPageScraper for the ticket page.
 * @see <a href="https://www.mehringhoftheater.de/programm/">Mehringhof-Theater Programm</a>
 */
@Component
class MehringhofWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, MehringhofProgrammPageScraper()::scrape, MehringhofTicketPageScraper()::scrape) {
    /** The programme gives whole hours ("20 Uhr"); the ticket page has the minute (#2505). */
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.START_TIME)

    override val eventSource: EventSource = EventSource.MEHRINGHOF

    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? = document.selectFirst(MONTH_LINK)?.absUrl("href")?.takeIf { it.isNotEmpty() }

    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            title = fallback.title,
            sourceId = fallback.sourceId,
            soldOut = primary.soldOut || fallback.soldOut
        )

    private companion object {
        /** Another month page in the menu: `a.level_2` under the programme entry, not the page itself. */
        const val MONTH_LINK = "a.level_2:not(.current)[href*=/programm/]"
    }
}

/**
 * German is the house language, stated by the theatre on https://www.mehringhoftheater.de/über-uns/:
 * "Wir versuchen als Veranstalter eine permanente Bestandsaufnahme des deutschsprachigen Kabaretts/Comedy
 * zu leisten" (#2584).
 */
val MEHRINGHOF_LIMITATIONS =
    VenueLimitations(
        EventSource.MEHRINGHOF,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme states one time per performance"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the ticket shop gives every performance the same 6 a.m. end"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the programme names no format; Kabarett, comedy, readings and song evenings share one table"),
        AcceptedLimitation(LimitedAspect.GENRE, "the programme names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the theatre presents every performance itself"),
        houseLanguage = SpokenLanguage.GERMAN
    )
