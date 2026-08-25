package de.norm.events.scraper.quasimodo

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Quasimodo Berlin's Events-Manager programme.
 *
 * Orchestrates the fetch → parse pipeline:
 * 1. Fetch the unpaginated `/events` listing via [HtmlFetcher] with conditional request support
 *    (ETag / Last-Modified).
 * 2. Discover every `a.event-item` card via [QuasimodoOverviewPageScraper] — the only source for
 *    the date and start time (its mobile date block carries a full `DD.MM.YYYY - HH:mm`).
 * 3. For each event, fetch its `/events/<slug>-<postId>` page and parse it via
 *    [QuasimodoDetailPageScraper] — the source for the category, promoter, prices, description
 *    and full-size poster.
 *
 * The programme is on the **`.club` domain**; `quasimodo.de` is only a splash page.
 *
 * @see QuasimodoOverviewPageScraper for listing parsing (date, genre, thumbnail, ticket).
 * @see QuasimodoDetailPageScraper for detail parsing (category, promoter, prices, description).
 * @see <a href="https://quasimodo.club/events">Quasimodo event listing</a>
 */
@Component
class QuasimodoWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.QUASIMODO

    private val overviewPageScraper = QuasimodoOverviewPageScraper()
    private val detailPageScraper = QuasimodoDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]).
     *
     * The detail page owns the category, promoter, prices, description and full-size poster. The
     * **listing owns the date and start time** — the detail page renders them as separate
     * `day`/`month`/`year` spans, while the listing's mobile block already carries a complete
     * `DD.MM.YYYY - HH:mm`, so the detail scraper deliberately does not parse them. Because the
     * category can flip an event to `PARTY`, the artist roster is rebuilt from the resolved type
     * so a party's event name is not left minted as a headliner by the listing's guess.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent {
        val eventType = primary.eventType ?: fallback.eventType
        return primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            startTime = primary.startTime ?: fallback.startTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            genre = primary.genre ?: fallback.genre,
            eventType = eventType,
            artists = buildArtistsForEventType(primary.title, subtitle = null, eventType = eventType)
        )
    }
}

/** Nothing this source withholds needs declaring (#715). */
val QUASIMODO_LIMITATIONS = VenueLimitations(EventSource.QUASIMODO)
