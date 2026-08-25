package de.norm.events.scraper.ritterbutzke

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
 * Website importer for Ritter Butzke's programme.
 *
 * Orchestrates the fetch → parse pipeline:
 * 1. Fetch the unpaginated `/events` listing via [HtmlFetcher] with conditional request support
 *    (ETag / Last-Modified).
 * 2. Discover every grid card via [RitterButzkeOverviewPageScraper] — title, rendered date and
 *    poster.
 * 3. For each event, fetch its `/event/DDMMYY-<Name>` page and parse it via
 *    [RitterButzkeDetailPageScraper] — the only source for the start time, ticket shop and DJ
 *    lineup.
 *
 * The `/calendarfile/<id>` links beside each date are `Disallow`ed by the venue's robots.txt and
 * are never fetched.
 *
 * @see RitterButzkeOverviewPageScraper for listing parsing (discovery, date, poster).
 * @see RitterButzkeDetailPageScraper for detail parsing (start time, ticket, lineup).
 * @see <a href="https://club.ritterbutzke.com/events">Ritter Butzke event listing</a>
 */
@Component
class RitterButzkeWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.RITTER_BUTZKE

    private val overviewPageScraper = RitterButzkeOverviewPageScraper()
    private val detailPageScraper = RitterButzkeDetailPageScraper()

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
     * Both pages render the same rendered-date-wins title and date, so the detail page simply wins
     * and the listing backstops it — including the poster, which the listing serves at the same
     * URL.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            eventType = primary.eventType ?: fallback.eventType,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

val RITTER_BUTZKE_LIMITATIONS =
    VenueLimitations(
        EventSource.RITTER_BUTZKE,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club publishes no categories; every night is a DJ programme")
    )
