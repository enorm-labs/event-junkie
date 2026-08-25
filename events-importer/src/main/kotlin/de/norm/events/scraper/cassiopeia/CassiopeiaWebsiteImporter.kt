package de.norm.events.scraper.cassiopeia

import de.norm.events.event.EventType
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Cassiopeia Berlin's Webflow-based event listing.
 *
 * Orchestrates the full fetch → parse pipeline for Cassiopeia:
 * 1. Fetches the overview page (`/club`) via [HtmlFetcher] with conditional
 *    request support (ETag / Last-Modified).
 * 2. Parses event listings from the overview page via [CassiopeiaOverviewPageScraper].
 * 3. For each event, fetches its detail page via [HtmlFetcher].
 * 4. Parses the detail page via [CassiopeiaDetailPageScraper] — this is the
 *    **primary** data source. The overview page provides discovery and fallback
 *    data for fields the detail page cannot supply.
 *
 * @see CassiopeiaOverviewPageScraper for overview page parsing (discovery + fallback)
 * @see CassiopeiaDetailPageScraper for detail page parsing (primary data source)
 * @see <a href="https://cassiopeia-berlin.de/club">Cassiopeia Club page</a>
 */
@Component
class CassiopeiaWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's past-event cutoff. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.CASSIOPEIA

    private val overviewPageScraper = CassiopeiaOverviewPageScraper(clock)
    private val detailPageScraper = CassiopeiaDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Fills missing fields in the [primary] (detail page) event with values
     * from the [fallback] (overview page) event.
     *
     * The detail page is authoritative for every field it provides. The
     * overview page only contributes values where the detail page returned
     * null or a default sentinel (e.g. missing genre or "OTHER" event type).
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            // Treat the detail page's "OTHER" as a weak signal: a more specific overview
            // type wins over it, but a specific detail type still takes precedence.
            eventType = primary.eventType?.takeIf { it != EventType.OTHER.name } ?: fallback.eventType,
            genre = primary.genre ?: fallback.genre,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != "SCHEDULED" } ?: fallback.status,
            description = primary.description ?: fallback.description,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            // Detail page artists include support acts from description paragraphs;
            // overview page only has the headliner. Prefer detail when available.
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

val CASSIOPEIA_LIMITATIONS =
    VenueLimitations(
        EventSource.CASSIOPEIA,
        AcceptedLimitation(LimitedAspect.PAGINATION, "only the first page of the listing is read")
    )
