package de.norm.events.scraper.urania

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Urania Berlin, the Schöneberg science and culture house.
 *
 * Reads `/kalender/` — the whole upcoming programme, server-rendered, no pagination — then
 * follows each item to its `/event/<slug>/` page for poster, prose and admission line.
 *
 * Two halls, the **Humboldtsaal** and the **Kleistsaal**, and no source states which one an
 * event is in: not the calendar, the event pages, the Reservix shop, nor the site's own Reservix
 * API. Events are imported against the house, which is what every source describes; splitting
 * would mean guessing or storing every event twice.
 *
 * @see UraniaCalendarPageScraper for the calendar parsing.
 * @see UraniaEventPageScraper for the event-page parsing.
 * @see <a href="https://www.urania.de/kalender/">Urania Berlin calendar</a>
 */
@Component
class UraniaWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource get() = EventSource.URANIA

    private val calendarPageScraper = UraniaCalendarPageScraper()
    private val eventPageScraper = UraniaEventPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = calendarPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = eventPageScraper.scrape(document, url)

    /**
     * Merges event-page data ([primary]) with calendar data ([fallback]). The event page is richer
     * and wins wherever it states something — the only one carrying poster, prose and price. The
     * **calendar owns the date and clock**: a machine-readable `data-day` token where the page
     * renders German prose, used whenever the page's own line fails to parse.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = fallback.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: primary.eventDate,
            startTime = fallback.startTime ?: primary.startTime,
            subtitle = primary.subtitle ?: fallback.subtitle,
            eventType = primary.eventType ?: fallback.eventType,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val URANIA_LIMITATIONS = VenueLimitations(EventSource.URANIA)
