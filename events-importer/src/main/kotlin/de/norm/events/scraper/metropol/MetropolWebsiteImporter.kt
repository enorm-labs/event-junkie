package de.norm.events.scraper.metropol

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Metropol Berlin's Events-Manager programme.
 *
 * 1. [HtmlFetcher] fetches the unpaginated `/events` listing conditionally (ETag / Last-Modified).
 * 2. [MetropolOverviewPageScraper] discovers every `li.event` row — the only source for the support acts.
 * 3. Each `/event/<iso-date-slug>` page via [MetropolDetailPageScraper] — promoter, subtitle,
 * poster, description, ticket link and the unambiguously labelled times.
 *
 * @see MetropolOverviewPageScraper for listing parsing (discovery, support acts, fallback).
 * @see MetropolDetailPageScraper for detail parsing (promoter, image, ticket, description).
 * @see <a href="https://metropol-berlin.de/events">Metropol event listing</a>
 */
@Component
class MetropolWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.METROPOL

    private val overviewPageScraper = MetropolOverviewPageScraper()
    private val detailPageScraper = MetropolDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]). The detail page wins on
     * everything it carries — notably the times, labelled explicitly (`Einlass: … // Beginn: …`)
     * where the listing implies them by position. The listing is authoritative for the **support
     * acts**, which the detail `h1` omits, so subtitle and roster are rebuilt: headliner from the
     * detail title, support acts from the listing, rather than either roster wholesale.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent {
        val subtitle = primary.subtitle ?: fallback.subtitle
        return primary.copy(
            // Only the listing renders a support line, so it supplies the subtitle when the detail page
            // has no tour name.
            subtitle = subtitle,
            // The slug date is on both pages; fall back only if the detail slug was unparseable.
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            eventType = primary.eventType ?: fallback.eventType,
            // Rebuild from the detail headliner plus the listing's support acts.
            artists = buildMetropolArtists(primary.title, fallback.subtitle, primary.eventType ?: fallback.eventType)
        )
    }
}

/** Nothing this source withholds needs declaring (#715). */
val METROPOL_LIMITATIONS = VenueLimitations(EventSource.METROPOL)
