package de.norm.events.scraper.zitadelle

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Zitadelle Spandau, whose programme is published as the Citadel Music
 * Festival — the open-air concert series that is the fortress's entire event calendar.
 *
 * Reads the `/events` listing (WordPress + Events Manager, rendered in full with no pagination),
 * then follows each card to its `/event/<YYYY-MM-DD-slug>` page for the doors time, tour title,
 * description, ticket link, presenters and any change notice.
 *
 * The season is small — under a dozen dates across one summer plus whatever is already announced
 * for the next — so the whole import is a listing fetch and a handful of detail fetches.
 *
 * @see ZitadelleOverviewPageScraper for the listing parser.
 * @see ZitadelleDetailPageScraper for the detail-page parser.
 */
@Component
class ZitadelleWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource get() = EventSource.ZITADELLE

    private val overviewPageScraper = ZitadelleOverviewPageScraper()
    private val detailPageScraper = ZitadelleDetailPageScraper()

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
     * The detail page is richer on everything it states, so it wins wherever it has a value — it is
     * the only source of the doors time, the tour title, the description, the ticket link and the
     * presenters, and it explains a changed date where the listing only badges it.
     *
     * The **listing owns the date**: the detail page renders it as long German prose where the card
     * carries a machine-readable `time[datetime]`. The listing also keeps the sold-out flag alive
     * for the same reason as the poster — both survive a detail fetch that degrades.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            startTime = primary.startTime ?: fallback.startTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            soldOut = primary.soldOut || fallback.soldOut,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val ZITADELLE_LIMITATIONS = VenueLimitations(EventSource.ZITADELLE)
