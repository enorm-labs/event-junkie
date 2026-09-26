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
 * 1. [HtmlFetcher] fetches `/event-list` conditionally (ETag / Last-Modified).
 * 2. [LogeOverviewPageScraper] discovers events from the embedded `wix-warmup-data` JSON —
 * authoritative for title, date, start time, image and artist roster.
 * 3. Each `/event-details/<slug>` page's schema.org `Event` JSON-LD via [LogeDetailPageScraper]
 * — primary for the ticket price and the confirmed status.
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
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page
     * (schema.org JSON-LD) is authoritative for price and status. The overview is authoritative for
     * the artist roster (the detail page renders none) and the event type, and supplies the poster
     * (its `mainImage.url` is the canonical original, versus the JSON-LD's resized variant).
     * Either page's date/start time backstops the other.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            // Detail pages carry the real date; fall back only if absent (sentinel).
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            startTime = primary.startTime ?: fallback.startTime,
            // Overview mainImage.url is the canonical original; prefer it over the JSON-LD resized variant.
            imageUrl = fallback.imageUrl ?: primary.imageUrl,
            // Event type and artists are derived on the overview only.
            eventType = fallback.eventType ?: primary.eventType,
            // The genre is the overview's venue default; the detail page sets none.
            genre = fallback.genre,
            artists = primary.artists.ifEmpty { fallback.artists },
            // The end is in the overview JSON alone (#1408).
            endDate = fallback.endDate,
            endTime = fallback.endTime
        )
}

val LOGE_LIMITATIONS =
    VenueLimitations(
        EventSource.LOGE,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue has no category field; a live-music venue, so an unmarked title defaults to a concert"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a title without a + separator can be a band or an event name, so no act is derived from one")
    )
