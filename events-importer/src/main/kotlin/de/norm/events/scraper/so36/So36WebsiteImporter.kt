package de.norm.events.scraper.so36

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
 * Website importer for SO36 Berlin's Ticket-Toaster shop platform.
 *
 * Orchestrates the full fetch → parse pipeline for SO36:
 * 1. Fetches the overview page (`/tickets`) via [HtmlFetcher] with conditional
 *    request support (ETag / Last-Modified). The configured source URL points
 *    straight at `/tickets` (the homepage 302-redirects there) to save the extra
 *    redirect hop, though the scraper client now follows redirects either way.
 * 2. Discovers every event and its detail URL via [So36OverviewPageScraper]
 *    (which also supplies the fallback title and date).
 * 3. For each event, fetches its `/produkte/…` detail page via [HtmlFetcher].
 * 4. Parses the detail page via [So36DetailPageScraper] — the primary source for
 *    type, subtitle, times, description, image, price, ticket link, and status.
 *
 * @see So36OverviewPageScraper for overview parsing (discovery, fallback data).
 * @see So36DetailPageScraper for detail parsing (the primary per-event source).
 * @see <a href="https://www.so36.com/tickets">SO36 program</a>
 */
@Component
class So36WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.SO36

    private val overviewPageScraper = So36OverviewPageScraper()
    private val detailPageScraper = So36DetailPageScraper()

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
     * The detail page is authoritative for every enriched field. The overview
     * only backstops the date: when the detail page carries no parseable
     * `startDate` (the [UNRESOLVED_EVENT_DATE] sentinel), the date parsed from the
     * overview product URL is used instead.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate
        )
}

val SO36_LIMITATIONS =
    VenueLimitations(
        EventSource.SO36,
        AcceptedLimitation(
            LimitedAspect.PRICE_BOX_OFFICE,
            "the shop publishes the presale price as microdata and does not expose a box-office price structurally"
        ),
        AcceptedLimitation(
            LimitedAspect.SOLD_OUT,
            "the JSON-LD offer reports `SoldOut` for the external shops most events sell through, even when those shops still have tickets, so it is not read"
        )
    )
