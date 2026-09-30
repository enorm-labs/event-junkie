package de.norm.events.scraper.zitadelle

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Zitadelle Spandau, whose programme is the Citadel Music Festival —
 * the open-air concert series that is the fortress's entire event calendar.
 *
 * Reads the `/events` listing (WordPress + Events Manager, in full, no pagination), then
 * follows each card to its `/event/<YYYY-MM-DD-slug>` page for doors time, tour title,
 * description, ticket link, presenters and any change notice. The season is small — under a
 * dozen dates across one summer plus what is announced for the next — so the whole import is
 * one listing fetch and a handful of detail fetches.
 *
 * @see ZitadelleOverviewPageScraper for the listing parser.
 * @see ZitadelleDetailPageScraper for the detail-page parser.
 */
@Component
class ZitadelleWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource get() = EventSource.ZITADELLE
    override val listsWholeProgramme: Boolean = true

    private val overviewPageScraper = ZitadelleOverviewPageScraper()
    private val detailPageScraper = ZitadelleDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)
}

/** Nothing this source withholds needs declaring (#715). */
val ZITADELLE_LIMITATIONS = VenueLimitations(EventSource.ZITADELLE)
