package de.norm.events.scraper.zebranotheater

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Zebrano-Theater in Friedrichshain, a static site maintained by hand.
 *
 * `programm.html` lists the evening programme about a year ahead, one table per month
 * ([ZebranoTheaterProgrammPageScraper]). Each evening's `programm/<yyyyMMdd>.shtml` page adds the
 * text and the photo ([ZebranoTheaterEventPageScraper]). A row announced before its page exists
 * links none and keeps the listing's fields. The children's programme on `kinderprogramm.html` is
 * not read.
 *
 * @see <a href="https://www.zebrano-theater.de/programm.html">Zebrano-Theater Programm</a>
 */
@Component
class ZebranoTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Zebrano-Theater programme", ZebranoTheaterProgrammPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ZEBRANO_THEATER
    override val listsWholeProgramme: Boolean = true

    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = ZebranoTheaterEventPageScraper()::scrape

    override fun hasEventPage(event: ScrapedEvent): Boolean = EVENT_PAGE.containsMatchIn(event.sourceUrl)

    private companion object {
        val EVENT_PAGE = Regex("""/programm/\d{8}[^/]*\.shtml$""")
    }
}

val ZEBRANO_THEATER_LIMITATIONS =
    VenueLimitations(
        EventSource.ZEBRANO_THEATER,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme prints one time per evening"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the house sells one price, by e-mail reservation or through its ticket shop"),
        AcceptedLimitation(LimitedAspect.GENRE, "the programme names a category per evening, not a musical style"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the house presents every evening itself")
    )
