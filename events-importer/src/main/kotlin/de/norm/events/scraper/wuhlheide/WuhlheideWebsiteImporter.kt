package de.norm.events.scraper.wuhlheide

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Parkbühne Wuhlheide's open-air programme.
 *
 * Orchestrates the fetch → parse pipeline:
 * 1. Fetch the unpaginated `/programm` page via [HtmlFetcher] with conditional request support
 *    (ETag / Last-Modified).
 * 2. Discover every `.show` via [WuhlheideOverviewPageScraper] — act, tour name, poster,
 *    sold-out flag and the ISO date carried by the event URL.
 * 3. For each event, fetch its `/programm/<act>/YYYY-MM-DD` page and parse it via
 *    [WuhlheideDetailPageScraper] — the only source for doors, start, price and promoter.
 *
 * @see WuhlheideOverviewPageScraper for listing parsing (discovery, subtitle, sold-out).
 * @see WuhlheideDetailPageScraper for detail parsing (times, price, promoter).
 * @see <a href="https://www.wuhlheide.de/programm">Parkbühne Wuhlheide programme</a>
 */
@Component
class WuhlheideWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.WUHLHEIDE

    private val overviewPageScraper = WuhlheideOverviewPageScraper()
    private val detailPageScraper = WuhlheideDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]).
     *
     * The detail page owns the times, price and promoter. The **listing owns the subtitle**: the
     * detail page's `h3` is not reliably a tour name — a show can put an admin notice there — so
     * it is never read, and the artist roster is rebuilt from the listing's subtitle so a
     * `Support:` line there still reaches the lineup. The listing also owns the sold-out flag,
     * which only its `.statusLabel` carries.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            subtitle = fallback.subtitle,
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            eventType = primary.eventType ?: fallback.eventType,
            soldOut = fallback.soldOut,
            artists = buildArtistsForEventType(primary.title, fallback.subtitle, primary.eventType ?: fallback.eventType)
        )
}

val WUHLHEIDE_LIMITATIONS =
    VenueLimitations(
        EventSource.WUHLHEIDE,
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the venue publishes no cancellations; its one badge, Ausverkauft, is a sold-out flag")
    )
