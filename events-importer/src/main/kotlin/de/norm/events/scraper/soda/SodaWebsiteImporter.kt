package de.norm.events.scraper.soda

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
import java.time.Clock

/**
 * Website importer for Soda Club Berlin's "disco2app" event listing.
 *
 * A discotheque in the Kulturbrauerei whose CMS exposes no JSON feed, so two HTML pages:
 * 1. [HtmlFetcher] fetches `/events` conditionally (ETag / Last-Modified).
 * 2. [SodaOverviewPageScraper] parses the `.event-snippet` cards — discovery list plus a
 * complete-enough fallback event (title, weekday-inferred date, flyer, ticket link).
 * 3. Each detail page via [SodaDetailPageScraper] — the primary source: schema.org `MusicEvent`
 * JSON-LD (exact date, start time, status, the shop's online price) plus description and
 * admission price from the markup.
 *
 * @see SodaOverviewPageScraper for overview parsing (discovery, year-less date fallback).
 * @see SodaDetailPageScraper for detail parsing (date, time, prices, description, status).
 * @see <a href="https://www.soda-berlin.de/events">Soda Club Berlin</a>
 */
@Component
class SodaWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's weekday-based year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.SODA

    private val overviewPageScraper = SodaOverviewPageScraper(clock)
    private val detailPageScraper = SodaDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page is
     * authoritative and carries everything the overview lacks (start time, prices, description,
     * status). Only the date (year-inferred there, exact here), flyer and ticket link fall back,
     * so a detail page missing one still yields a complete event. Type is `PARTY` on both pages
     * and neither derives artists or promoters, so nothing else to reconcile.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl
        )
}

val SODA_LIMITATIONS =
    VenueLimitations(
        EventSource.SODA,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the Einlass info box states an age limit, not a doors time"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the JSON-LD performer is the placeholder Unbekannt on every night"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the JSON-LD `organizer` is the venue itself on every night")
    )
