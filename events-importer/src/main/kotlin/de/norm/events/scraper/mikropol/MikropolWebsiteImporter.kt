package de.norm.events.scraper.mikropol

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Mikropol Berlin's Events-Manager concert listing.
 *
 * Mikropol is a WordPress/Events-Manager site whose theme renders no schema.org JSON-LD and
 * whose Events-Manager REST API is not exposed for anonymous reads, so it is scraped as two
 * HTML pages:
 * 1. Fetches the `/events/` overview page via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified).
 * 2. Parses the event cards via [MikropolOverviewPageScraper] — the source for the discovery
 *    list, date, start/doors times, status, sold-out flag, and headliner/support artists.
 * 3. For each event, fetches and parses its detail page via [MikropolDetailPageScraper] — the
 *    source for the description, image, and Eventim ticket URL.
 *
 * @see MikropolOverviewPageScraper for overview parsing (date, status, artists, fallback).
 * @see MikropolDetailPageScraper for detail parsing (description, image, ticket URL).
 * @see <a href="https://mikropol-berlin.de/events/">Mikropol Berlin</a>
 */
@Component
class MikropolWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.MIKROPOL

    private val overviewPageScraper = MikropolOverviewPageScraper()
    private val detailPageScraper = MikropolDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges detail-page data ([primary]) with overview-page data ([fallback]).
     *
     * The detail page is authoritative and carries the fields the overview lacks (description,
     * image, ticket URL). The fields both pages share (date, times, status, sold-out, artists)
     * prefer the detail value and fall back to the overview — so if a specific field is missing
     * on the detail page, the overview still supplies it.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            subtitle = primary.subtitle ?: fallback.subtitle,
            description = primary.description ?: fallback.description,
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            // A sold-out/cancelled badge or relocation note may render on only one of the pages, so keep it
            // whenever either page reports it.
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != EventStatus.SCHEDULED.name } ?: fallback.status,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val MIKROPOL_LIMITATIONS = VenueLimitations(EventSource.MIKROPOL)
