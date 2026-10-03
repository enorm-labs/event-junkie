package de.norm.events.scraper.quasimodo

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import org.springframework.stereotype.Component

/**
 * Website importer for Quasimodo Berlin's Events-Manager programme.
 *
 * 1. [HtmlFetcher] fetches the unpaginated `/events` listing conditionally (ETag / Last-Modified).
 * 2. [QuasimodoOverviewPageScraper] discovers every `a.event-item` card — the only source for
 * date and start time (its mobile date block carries a full `DD.MM.YYYY - HH:mm`).
 * 3. Each `/events/<slug>-<postId>` page via [QuasimodoDetailPageScraper] — category, promoter,
 * prices, description and full-size poster.
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
) : AbstractTwoPageWebsiteImporter(htmlFetcher, QuasimodoOverviewPageScraper()::scrape, QuasimodoDetailPageScraper()::scrape) {
    /** The listing's type is a guess from the title; the event page names the venue's category (#2505). */
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.EVENT_TYPE)

    override val eventSource: EventSource = EventSource.QUASIMODO
    override val listsWholeProgramme: Boolean = true

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]). The detail page owns
     * category, promoter, prices, description and full-size poster. The **listing owns the date
     * and start time** — the detail page renders them as separate `day`/`month`/`year` spans while
     * the listing's mobile block carries a complete `DD.MM.YYYY - HH:mm`, so the detail scraper
     * does not parse them. Because the category can flip an event to `PARTY`, the roster is
     * rebuilt from the resolved type so a party's event name is not left minted as a headliner by
     * the listing's guess.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent {
        val eventType = primary.eventType ?: fallback.eventType
        return primary.withGapsFrom(fallback).copy(
            artists = buildArtistsForEventType(primary.title, subtitle = null, eventType = eventType)
        )
    }
}

/** Nothing this source withholds needs declaring (#715). */
val QUASIMODO_LIMITATIONS = VenueLimitations(EventSource.QUASIMODO)
