package de.norm.events.scraper.hole44

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
 * Website importer for Hole 44 Berlin's Events-Manager concert listing.
 *
 * Hole 44 is a WordPress/Events-Manager site whose Events-Manager REST API is not
 * exposed for anonymous reads, so it is scraped as two HTML pages:
 * 1. Fetches the `/events/` overview page via [HtmlFetcher] with conditional-request
 *    support (ETag / Last-Modified).
 * 2. Parses the event items via [Hole44OverviewPageScraper] — the source for the
 *    discovery list, date, start time, genre, status, and headliner/support artists.
 * 3. For each event, fetches and parses its detail page via [Hole44DetailPageScraper]
 *    — the source for the description, image, promoter, and doors time.
 *
 * @see Hole44OverviewPageScraper for overview parsing (date, status, artists, fallback).
 * @see Hole44DetailPageScraper for detail parsing (description, image, promoter, doors).
 * @see <a href="https://hole-berlin.de/events/">Hole 44 Berlin</a>
 */
@Component
class Hole44WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.HOLE44

    private val overviewPageScraper = Hole44OverviewPageScraper()
    private val detailPageScraper = Hole44DetailPageScraper()

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
     * The detail page is authoritative and carries the fields the overview lacks
     * (description, image, promoter, doors time). The fields both pages share (date,
     * start time, genre, status, artists) prefer the detail value and fall back to
     * the overview — so if a specific field is missing on the detail page, the
     * overview still supplies it.
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
            genre = primary.genre ?: fallback.genre,
            // A relocation/cancellation note may render on the overview but not the detail header, so keep
            // any non-scheduled overview status when the detail page reports plain SCHEDULED.
            status = primary.status.takeIf { it != EventStatus.SCHEDULED.name } ?: fallback.status,
            promoters = primary.promoters.ifEmpty { fallback.promoters },
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val HOLE44_LIMITATIONS = VenueLimitations(EventSource.HOLE44)
