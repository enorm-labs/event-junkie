package de.norm.events.scraper.astra

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Astra Kulturhaus' Kulturhäuser-platform event listing.
 *
 * Orchestrates the full fetch → parse pipeline for Astra:
 * 1. Fetches the overview page (the homepage `/`) via [HtmlFetcher] with
 *    conditional request support (ETag / Last-Modified).
 * 2. Parses event articles from the overview page via [AstraOverviewPageScraper]
 *    — this is the primary source for the event type and sold-out status, and
 *    provides discovery + fallback data for every other field.
 * 3. For each event, fetches its detail page via [HtmlFetcher].
 * 4. Parses the detail page via [AstraDetailPageScraper] — the primary source
 *    for promoter, prices, ticket URL, and description.
 *
 * @see AstraOverviewPageScraper for overview page parsing (event type, discovery, fallback)
 * @see AstraDetailPageScraper for detail page parsing (promoter, prices, ticket, description)
 * @see <a href="https://www.astra-berlin.de/">Astra Kulturhaus</a>
 */
@Component
class AstraWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.ASTRA

    private val overviewPageScraper = AstraOverviewPageScraper()
    private val detailPageScraper = AstraDetailPageScraper()

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
     * The detail page is authoritative for promoter, prices, ticket URL, and
     * description (which the overview lacks). The overview page is authoritative
     * for the event type and artists, and supplies the date for the dateless
     * featured teaser.
     *
     * The detail page *can* also carry a `kind` label, but it is the raw per-day
     * value — uncorrected by [AstraOverviewPageScraper]'s festival-day
     * normalization — so the overview type takes precedence and the detail value
     * is only a fallback when the overview had no label.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            // Detail pages carry the real date; fall back only if it was absent (sentinel).
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            // Overview is authoritative for the type (it normalizes mislabeled festival days);
            // the detail kind is only a fallback when the overview had no label.
            eventType = fallback.eventType ?: primary.eventType,
            subtitle = primary.subtitle ?: fallback.subtitle,
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != "SCHEDULED" } ?: fallback.status,
            // Artists are only extracted on the overview page (needs subtitle + kind).
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val ASTRA_LIMITATIONS = VenueLimitations(EventSource.ASTRA)
