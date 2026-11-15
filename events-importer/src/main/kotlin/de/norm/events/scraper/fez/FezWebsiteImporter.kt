package de.norm.events.scraper.fez

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the stage programme of FEZ-Berlin, the children's and youth centre in the Wuhlheide. The stage
 * schedule of the Astrid-Lindgren-Bühne is a server-rendered TYPO3 list (`brcalendar2`) of 50 performances a page, about
 * four pages ahead. It covers the main stage and the Studiobühne. The rest of `/programm` (pool courses, exhibitions,
 * day passes, the Puppentheaterfest) is not read (#5077).
 *
 * The pager links carry a `cHash`, so the next page is the link the page itself prints for `curPage` + 1, never a URL
 * built here. FEZ has no event page of its own: the title links to the ticket shop.
 *
 * @see FezSpielplanPageScraper for the listing.
 * @see <a href="https://fez-berlin.de/orte/astrid-lindgren-buehne/spielplan">FEZ Spielplan</a>
 */
@Component
class FezWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "FEZ-Berlin", FezSpielplanPageScraper()::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.FEZ

    override val maxListingPages: Int = 8

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? {
        val current =
            CUR_PAGE
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 1
        val next =
            document
                .select("a[href*=curPage]")
                .firstOrNull { CUR_PAGE.find(it.attr("href"))?.groupValues?.get(1) == "${current + 1}" }
                ?.absUrl("href")
        if (next == null && current == 1 && document.select("div.termin").size >= PAGE_SIZE) {
            logger.warn { "No FEZ page 2 link on a full page $url, importing page 1 only" }
        }
        return next
    }

    private companion object {
        const val PAGE_SIZE = 50
        val CUR_PAGE = Regex("""curPage(?:%5D|])=(\d+)""")
    }
}

val FEZ_LIMITATIONS =
    VenueLimitations(
        EventSource.FEZ,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the listing has no text, and fez has no event page of its own", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.PRICE, "the listing states no price; the ticket shop holds it", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes only the start time", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site publishes only the start time", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.GENRE, "the categories name a format, such as figurentheater, not a music genre", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a show names its company, not its cast", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no presenter per performance", verified = "2026-11-15")
    )
