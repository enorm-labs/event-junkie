package de.norm.events.scraper.metropol

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
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
) : AbstractTwoPageWebsiteImporter(htmlFetcher, MetropolOverviewPageScraper()::scrape, MetropolDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.METROPOL
    override val listsWholeProgramme: Boolean = true

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
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            // Rebuild from the detail headliner plus the listing's support acts.
            artists = buildMetropolArtists(primary.title, fallback.subtitle, primary.eventType ?: fallback.eventType)
        )
}

val METROPOL_LIMITATIONS =
    VenueLimitations(
        EventSource.METROPOL,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no figure; tickets are sold through an Eventim link"),
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the detail page's TAGS field is empty, and its one category names the event type (Konzert, Party), not a style"
        )
    )
