package de.norm.events.scraper.altekantine

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Alte Kantine (Kulturbrauerei) Berlin's WordPress programme.
 *
 * The WP REST API is locked down (iThemes Security returns 401 for anonymous reads), so two
 * HTML pages:
 * 1. [HtmlFetcher] fetches the homepage conditionally (ETag / Last-Modified).
 * 2. [AlteKantineOverviewPageScraper] parses the Content Views grid — discovery list, date,
 * start time, title and act line.
 * 3. Each `?p=<id>` post via [AlteKantineDetailPageScraper] — kind, price, description, image and DJ.
 *
 * @see AlteKantineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see AlteKantineDetailPageScraper for detail parsing (kind, price, image, DJ).
 * @see <a href="https://alte-kantine.eu/">Alte Kantine</a>
 */
@Component
class AlteKantineWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for year inference on the year-less dates; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.ALTE_KANTINE

    private val overviewPageScraper = AlteKantineOverviewPageScraper(clock)
    private val detailPageScraper = AlteKantineDetailPageScraper(clock)

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
     * authoritative and carries what the overview lacks (description, image, price, DJ). The
     * subtitle lives only on the overview, so it is always filled from there. Shared fields (date,
     * start time, event type) prefer the detail value and fall back to the overview.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            subtitle = primary.subtitle ?: fallback.subtitle,
            startTime = primary.startTime ?: fallback.startTime,
            eventType = primary.eventType ?: fallback.eventType,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val ALTE_KANTINE_LIMITATIONS = VenueLimitations(EventSource.ALTE_KANTINE)
