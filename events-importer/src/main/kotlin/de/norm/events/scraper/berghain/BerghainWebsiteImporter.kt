package de.norm.events.scraper.berghain

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Berghain's server-rendered programme.
 *
 * One importer serves both source rows on the identical template — the main `/de/program/`
 * page (Berghain building floors → parties) and `/de/program/kantine-am-berghain/` (concert
 * hall). Each row carries its own URL, ETag and venue; both dispatch via [EventSource.BERGHAIN].
 *
 * Inherited from [AbstractTwoPageWebsiteImporter]:
 * 1. Fetch the overview and discover events via [BerghainOverviewPageScraper] (authoritative
 * for title, date, times, floor and the lineup).
 * 2. Each `/de/event/<id>/` page via [BerghainDetailPageScraper] for image, ticket link,
 * prices and description.
 * 3. Merge: the detail page is primary, the overview fills gaps — crucially the artist lineup,
 * which only the overview parses cleanly.
 *
 * @see BerghainOverviewPageScraper for overview parsing (discovery + lineup + fallback).
 * @see BerghainDetailPageScraper for detail parsing (image, prices, ticket, description).
 * @see <a href="https://www.berghain.berlin/de/program/">Berghain programme</a>
 */
@Component
class BerghainWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's past-event cutoff; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.BERGHAIN

    private val overviewPageScraper = BerghainOverviewPageScraper(clock)
    private val detailPageScraper = BerghainDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Fills what the detail page could not supply from the overview event. The detail page is
     * authoritative for everything it parses; the overview contributes only where it returned null
     * — most importantly the lineup, which the detail page does not parse (see [BerghainDetailPageScraper]).
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            subtitle = primary.subtitle ?: fallback.subtitle,
            eventType = primary.eventType ?: fallback.eventType,
            genre = primary.genre ?: fallback.genre,
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            description = primary.description ?: fallback.description,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            pricePresale = primary.pricePresale ?: fallback.pricePresale,
            priceBoxOffice = primary.priceBoxOffice ?: fallback.priceBoxOffice,
            soldOut = primary.soldOut || fallback.soldOut,
            // The detail page does not parse the lineup — the overview's artists are authoritative.
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val BERGHAIN_LIMITATIONS = VenueLimitations(EventSource.BERGHAIN)
