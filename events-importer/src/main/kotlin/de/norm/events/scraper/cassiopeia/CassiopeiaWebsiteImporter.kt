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
 * Website importer for Cassiopeia Berlin's Webflow listing: fetch `/club` via [HtmlFetcher]
 * with conditional headers, parse via [CassiopeiaOverviewPageScraper], fetch each detail page,
 * parse via [CassiopeiaDetailPageScraper], the primary source; the overview supplies discovery
 * and fallback.
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
     * Fills missing fields in the [primary] detail event from the [fallback] overview event. The
     * detail page is authoritative where it provides a value; the overview contributes on null or a
     * default sentinel (missing genre, "OTHER" type).
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            // The detail page's "OTHER" is a weak signal: a more specific overview type wins over it.
            eventType = primary.eventType?.takeIf { it != EventType.OTHER.name } ?: fallback.eventType,
            genre = primary.genre ?: fallback.genre,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != "SCHEDULED" } ?: fallback.status,
            description = primary.description ?: fallback.description,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            // Detail artists include support acts; the overview has only the headliner.
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

val CASSIOPEIA_LIMITATIONS =
    VenueLimitations(
        EventSource.CASSIOPEIA,
        AcceptedLimitation(LimitedAspect.PAGINATION, "only the first page of the listing is read")
    )
