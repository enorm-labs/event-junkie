package de.norm.events.scraper.lido

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

    /**
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page is
     * authoritative for description, prices, ticket URL and image; the overview supplies date,
     * sold-out flag, status and artist roster, which the detail header omits. Shared fields
     * (title, subtitle, type, times, presenters) prefer the detail value and fall back.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            // The detail header carries no date; fall back to the overview's (sentinel otherwise).
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            eventType = primary.eventType ?: fallback.eventType,
            subtitle = primary.subtitle ?: fallback.subtitle,
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            // The detail header has no status badge, so sold-out/status come from the overview.
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != EventStatus.SCHEDULED.name } ?: fallback.status,
            promoters = primary.promoters.ifEmpty { fallback.promoters },
            promoterWebsites = primary.promoterWebsites.ifEmpty { fallback.promoterWebsites },
            // Artists come from the overview only (needs subtitle + type).
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val LIDO_LIMITATIONS = VenueLimitations(EventSource.LIDO)
