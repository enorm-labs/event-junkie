package de.norm.events.scraper.lido

import de.norm.events.event.EventStatus
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
 * Website importer for Lido Berlin's Kulturhäuser-platform event listing.
 *
 * Same platform as Astra Kulturhaus, different theme, so it shares the pipeline shape, not
 * the selectors:
 * 1. [HtmlFetcher] fetches the overview (the homepage `/`) conditionally (ETag / Last-Modified).
 * 2. [LidoOverviewPageScraper] parses the articles — date, event type, sold-out flag, status,
 * presenters and artist roster.
 * 3. Each detail page via [LidoDetailPageScraper] — description, prices, ticket URL and image.
 *
 * @see LidoOverviewPageScraper for overview parsing (date, type, status, artists).
 * @see LidoDetailPageScraper for detail parsing (description, prices, ticket, image).
 * @see <a href="https://www.lido-berlin.de/">Lido Berlin</a>
 */
@Component
class LidoWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.LIDO
    override val listsWholeProgramme: Boolean = true

    private val overviewPageScraper = LidoOverviewPageScraper()
    private val detailPageScraper = LidoDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)
}

val LIDO_LIMITATIONS =
    VenueLimitations(
        EventSource.LIDO,
        AcceptedLimitation(LimitedAspect.GENRE, "the venue labels each event only Concert or Party; style is described only in the prose")
    )
