package de.norm.events.scraper.mikropol

import de.norm.events.event.EventStatus
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
 * Website importer for Mikropol Berlin's Events-Manager concert listing.
 *
 * WordPress/Events-Manager with no JSON-LD and the REST API not exposed for anonymous reads,
 * so two HTML pages:
 * 1. [HtmlFetcher] fetches `/events/` conditionally (ETag / Last-Modified).
 * 2. [MikropolOverviewPageScraper] parses the cards — discovery list, date, start/doors times,
 * status, sold-out flag, and headliner/support artists.
 * 3. Each detail page via [MikropolDetailPageScraper] — description, image and Eventim ticket URL.
 *
 * @see MikropolOverviewPageScraper for overview parsing (date, status, artists, fallback).
 * @see MikropolDetailPageScraper for detail parsing (description, image, ticket URL).
 * @see <a href="https://mikropol-berlin.de/events/">Mikropol Berlin</a>
 */
@Component
class MikropolWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.MIKROPOL

    private val overviewPageScraper = MikropolOverviewPageScraper()
    private val detailPageScraper = MikropolDetailPageScraper()

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
     * authoritative and carries what the overview lacks (description, image, ticket URL); shared
     * fields (date, times, status, sold-out, artists) prefer the detail value and fall back.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            subtitle = primary.subtitle ?: fallback.subtitle,
            description = primary.description ?: fallback.description,
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            // A sold-out/cancelled badge or relocation note may render on only one page, so keep it
            // whenever either page reports it.
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != EventStatus.SCHEDULED.name } ?: fallback.status,
            statusNote = primary.statusNote ?: fallback.statusNote,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

val MIKROPOL_LIMITATIONS =
    VenueLimitations(
        EventSource.MIKROPOL,
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style; its only category is Konzert or Club")
    )
