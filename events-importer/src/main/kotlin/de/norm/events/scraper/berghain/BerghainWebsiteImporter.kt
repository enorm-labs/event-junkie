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
 * A single importer serves both source rows built on the identical page template —
 * the main `/de/program/` page (Berghain building floors → parties) and the
 * `/de/program/kantine-am-berghain/` concert-hall page. Each row carries its own
 * URL, ETag and venue; both dispatch here via [EventSource.BERGHAIN].
 *
 * The pipeline (inherited from [AbstractTwoPageWebsiteImporter]):
 * 1. Fetch the overview page and discover events via [BerghainOverviewPageScraper]
 *    (the authoritative source for title, date, times, floor and the lineup).
 * 2. For each event, fetch its `/de/event/<id>/` detail page and parse it via
 *    [BerghainDetailPageScraper] for the image, ticket link, prices and description.
 * 3. Merge: the detail page is primary, with the overview filling any gaps —
 *    crucially the artist lineup, which only the overview parses cleanly.
 *
 * @see BerghainOverviewPageScraper for overview parsing (discovery + lineup + fallback).
 * @see BerghainDetailPageScraper for detail parsing (image, prices, ticket, description).
 * @see <a href="https://www.berghain.berlin/de/program/">Berghain programme</a>
 */
@Component
class BerghainWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's past-event cutoff. Defaults to the system clock; override in tests. */
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
     * Fills fields the detail page could not supply from the overview event. The
     * detail page is authoritative for everything it parses; the overview only
     * contributes where the detail returned null — most importantly the artist
     * lineup, which the detail page does not parse (see [BerghainDetailPageScraper]).
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
