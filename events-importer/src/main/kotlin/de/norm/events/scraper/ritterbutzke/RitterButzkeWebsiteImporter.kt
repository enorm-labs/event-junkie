package de.norm.events.scraper.ritterbutzke

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Ritter Butzke's programme.
 *
 * 1. [HtmlFetcher] fetches the unpaginated `/events` listing conditionally (ETag / Last-Modified).
 * 2. [RitterButzkeOverviewPageScraper] discovers every grid card — title, rendered date and poster.
 * 3. Each `/event/DDMMYY-<Name>` page via [RitterButzkeDetailPageScraper] — the only source for
 * start time, ticket shop and DJ lineup.
 *
 * The `/calendarfile/<id>` links beside each date are `Disallow`ed by robots.txt and never fetched.
 *
 * @see RitterButzkeOverviewPageScraper for listing parsing (discovery, date, poster).
 * @see RitterButzkeDetailPageScraper for detail parsing (start time, ticket, lineup).
 * @see <a href="https://club.ritterbutzke.com/events">Ritter Butzke event listing</a>
 */
@Component
class RitterButzkeWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.RITTER_BUTZKE
    override val listsWholeProgramme: Boolean = true

    private val overviewPageScraper = RitterButzkeOverviewPageScraper()
    private val detailPageScraper = RitterButzkeDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)
}

val RITTER_BUTZKE_LIMITATIONS =
    VenueLimitations(
        EventSource.RITTER_BUTZKE,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club publishes no categories; every night is a DJ programme"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the club sells through a third party and prints no figure; a night is flagged free only when its title says so"
        ),
        houseGenre = "Techno, House"
    )
