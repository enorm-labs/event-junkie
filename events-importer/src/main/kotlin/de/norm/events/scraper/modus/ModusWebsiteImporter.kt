package de.norm.events.scraper.modus

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Modus Berlin's programme.
 *
 * 1. [HtmlFetcher] fetches the unpaginated `/events` page conditionally (ETag / Last-Modified).
 * 2. [ModusOverviewPageScraper] discovers every `.event-item` tile — title, rendered date and poster.
 * 3. Each `/event/DDMMYY-<Name>` page via [ModusDetailPageScraper] — start time, ticket link
 * and description.
 *
 * @see ModusOverviewPageScraper for listing parsing (discovery, date, poster).
 * @see ModusDetailPageScraper for detail parsing (times, ticket, description).
 * @see <a href="https://modus-berlin.de/events">Modus event listing</a>
 */
@Component
class ModusWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.MODUS

    private val overviewPageScraper = ModusOverviewPageScraper()
    private val detailPageScraper = ModusDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]). Both render the same
     * date and title, so the detail page wins and the listing backstops it. The poster comes from
     * the listing when the detail page has none; the listing's date fills in only if the detail
     * `h2` was unparseable.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            eventType = primary.eventType ?: fallback.eventType,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val MODUS_LIMITATIONS = VenueLimitations(EventSource.MODUS)
