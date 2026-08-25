package de.norm.events.scraper.loge

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
 * Website importer for Loge's Wix Events listing.
 *
 * Orchestrates the fetch → parse pipeline for Loge:
 * 1. Fetches the overview page (`/event-list`) via [HtmlFetcher] with conditional
 *    request support (ETag / Last-Modified).
 * 2. Discovers events from the embedded `wix-warmup-data` JSON via
 *    [LogeOverviewPageScraper] — the authoritative source for title, date, start
 *    time, image, and the artist roster.
 * 3. For each event, fetches its `/event-details/<slug>` page and parses the
 *    schema.org `Event` JSON-LD via [LogeDetailPageScraper] — the primary source
 *    for the ticket price and the confirmed scheduling status.
 *
 * @see LogeOverviewPageScraper for overview parsing (discovery, artists, fallback)
 * @see LogeDetailPageScraper for detail parsing (price, status)
 * @see <a href="https://www.loge-berlin.org/event-list">Loge event listing</a>
 */
@Component
class LogeWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.LOGE

    private val overviewPageScraper = LogeOverviewPageScraper()
    private val detailPageScraper = LogeDetailPageScraper()

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
     * The detail page (schema.org JSON-LD) is authoritative for the ticket price
     * and the scheduling status. The overview page is authoritative for the
     * artist roster (the detail page renders none) and the event type, and
     * supplies the poster image (its `mainImage.url` is the canonical original,
     * versus the detail JSON-LD's resized variant). Either page's date/start time
     * backstops the other.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            // Detail pages carry the real date; fall back only if it was absent (sentinel).
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            startTime = primary.startTime ?: fallback.startTime,
            // Overview mainImage.url is the canonical original; prefer it over the JSON-LD resized variant.
            imageUrl = fallback.imageUrl ?: primary.imageUrl,
            // Event type and artists are only derived on the overview page.
            eventType = fallback.eventType ?: primary.eventType,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

val LOGE_LIMITATIONS =
    VenueLimitations(
        EventSource.LOGE,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue has no category field; a live-music venue, so an unmarked title defaults to a concert"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a title without a + separator can be a band or an event name, so no act is derived from one")
    )
