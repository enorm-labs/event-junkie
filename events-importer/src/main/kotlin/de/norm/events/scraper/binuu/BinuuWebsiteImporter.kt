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
 * Website importer for Bi Nuu's SvelteKit/PocketBase listing: fetch `/de/events` via
 * [HtmlFetcher] with conditional headers, discover events from `data.events[]` via
 * [BinuuOverviewPageScraper], then fetch each detail page and parse `data.item` via
 * [BinuuDetailPageScraper], a superset of the overview entry, so the merge prefers it and uses
 * the overview as a safety net.
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
     * Merges detail data ([primary]) over overview data ([fallback]): the detail payload wins for
     * every field; the overview backstops date, image, subtitle, start time, status and the
     * relocation note, and the sold-out flag is OR-ed.
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
            status = primary.status.takeIf { it != "SCHEDULED" } ?: fallback.status,
            statusNote = primary.statusNote ?: fallback.statusNote
        )
}

val BINUU_LIMITATIONS =
    VenueLimitations(
        EventSource.BINUU,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the SvelteKit payload carries no category field, and neither does anywhere else on the site")
    )
