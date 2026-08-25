package de.norm.events.scraper.binuu

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
 * Website importer for Bi Nuu's SvelteKit/PocketBase event listing.
 *
 * Orchestrates the fetch → parse pipeline for Bi Nuu:
 * 1. Fetches the listing page (`/de/events`) via [HtmlFetcher] with conditional
 *    request support (ETag / Last-Modified).
 * 2. Discovers events from the embedded `data.events[]` payload via
 *    [BinuuOverviewPageScraper].
 * 3. For each event, fetches its detail page and parses the richer `data.item`
 *    payload via [BinuuDetailPageScraper] — the primary source for doors,
 *    description, ticket URL, promoters, and the artist roster.
 *
 * The detail payload is a superset of the overview entry, so the merge simply
 * prefers detail data and uses the overview only as a safety net when a detail
 * page cannot be fetched.
 *
 * @see BinuuOverviewPageScraper for overview parsing (discovery, fallback)
 * @see BinuuDetailPageScraper for detail parsing (doors, description, tickets, promoters, artists)
 * @see <a href="https://binuu.de/de/events">Bi Nuu event listing</a>
 */
@Component
class BinuuWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.BINUU

    private val overviewPageScraper = BinuuOverviewPageScraper()
    private val detailPageScraper = BinuuDetailPageScraper()

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
     * The detail payload already carries everything the overview does plus more,
     * so it wins for every field; the overview only backstops the date, image,
     * subtitle, and start time if a detail page ever omits them, and the sold-out
     * flag is OR-ed so it counts wherever it appears.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            subtitle = primary.subtitle ?: fallback.subtitle,
            startTime = primary.startTime ?: fallback.startTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != "SCHEDULED" } ?: fallback.status
        )
}

val BINUU_LIMITATIONS =
    VenueLimitations(
        EventSource.BINUU,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the SvelteKit payload carries no category field, and neither does anywhere else on the site")
    )
