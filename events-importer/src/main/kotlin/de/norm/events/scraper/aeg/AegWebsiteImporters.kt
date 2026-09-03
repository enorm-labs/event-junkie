package de.norm.events.scraper.aeg

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Shared importer for the two Berlin AEG venues, which run one Carbonhouse tenant and therefore
 * one listing shape: [EventSource.UBER_ARENA] and [EventSource.UBER_EATS_MUSIC_HALL].
 *
 * Each reads its own `/events/all` page — server-rendered and unpaginated — keeps everything the
 * platform does not file as sport, then follows each row to its
 * `/events/detail/<slug>/<YYYY-MM-DD-HHMM>` page for the doors time, description and ticket link.
 *
 * @see AegOverviewPageScraper for the listing (discovery, category, date, price).
 * @see AegDetailPageScraper for the detail pages (doors, description, ticket).
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the venue importers below it; an instance of it alone names no venue.
abstract class AbstractAegVenueImporter(
    htmlFetcher: HtmlFetcher,
    override val eventSource: EventSource
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    private val overviewPageScraper = AegOverviewPageScraper()
    private val detailPageScraper = AegDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url, eventSource)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url, eventSource)

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]).
     *
     * The **listing wins on title, status, date, start time, category, price and thumbnail** — the
     * detail page states none of them cleanly: its heading appends "in der <venue>" to the act
     * name, and it renders neither a date nor a cancellation this parser reads. The detail page
     * contributes only the doors time, the description and the ticket link. The artist roster
     * therefore also comes from the listing, which is where the clean title lives.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            title = fallback.title,
            status = fallback.status,
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            startTime = primary.startTime ?: fallback.startTime,
            eventType = primary.eventType ?: fallback.eventType,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            pricePresale = primary.pricePresale ?: fallback.pricePresale,
            priceNote = primary.priceNote ?: fallback.priceNote,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/**
 * Website importer for Uber Arena. Home to ALBA Berlin and the Eisbären, so a large share of its
 * listing is sport, which is deliberately not imported — its concert, show and comedy count is
 * well below the number of rows the page shows.
 */
@Component
class UberArenaWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractAegVenueImporter(htmlFetcher, EventSource.UBER_ARENA)

/**
 * Website importer for the Uber Eats Music Hall — the arena's smaller neighbour, whose listing
 * omits the category *name* the arena publishes and abbreviates its months in German.
 */
@Component
class UberEatsMusicHallWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractAegVenueImporter(htmlFetcher, EventSource.UBER_EATS_MUSIC_HALL)

/** Nothing this source withholds needs declaring (#715). */
val AEG_LIMITATIONS =
    VenueLimitations(
        sources =
            setOf(
                EventSource.UBER_ARENA,
                EventSource.UBER_EATS_MUSIC_HALL
            )
    )
