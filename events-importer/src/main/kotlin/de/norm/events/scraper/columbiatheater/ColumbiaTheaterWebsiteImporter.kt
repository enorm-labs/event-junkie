package de.norm.events.scraper.columbiatheater

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Columbia Theater Berlin's homepage programme.
 *
 * Columbia Theater is a WordPress concert hall whose theme embeds no schema.org JSON-LD and whose
 * REST API is disabled site-wide (every `/wp-json/` route answers `401 rest_disabled`), so it is
 * scraped as two HTML pages:
 * 1. Fetches the homepage — which *is* the full upcoming listing — via [HtmlFetcher] with
 *    conditional-request support (the site currently sends neither ETag nor Last-Modified, so in
 *    practice every run is a full fetch).
 * 2. Parses the event cards via [ColumbiaTheaterOverviewPageScraper] — the source for the
 *    discovery list, date, title, tour/support subtitle, poster, status and lineup.
 * 3. For each event, fetches and parses its `/event/YYYYMMDD-<slug>/` detail page via
 *    [ColumbiaTheaterDetailPageScraper] — the source for the doors/start times, description,
 *    ticket URL and media presenters.
 *
 * @see ColumbiaTheaterOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see ColumbiaTheaterDetailPageScraper for detail parsing (times, blurb, tickets, presenters).
 * @see <a href="https://columbia-theater.de/">Columbia Theater Berlin</a>
 */
@Component
class ColumbiaTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.COLUMBIA_THEATER

    private val overviewPageScraper = ColumbiaTheaterOverviewPageScraper()
    private val detailPageScraper = ColumbiaTheaterDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges detail-page data ([primary]) with overview-page data ([fallback]).
     *
     * The detail page is authoritative and carries the fields the overview lacks (times,
     * description, ticket URL, presenters). The fields both pages share (date, subtitle, image,
     * status, artists) prefer the detail value and fall back to the overview — so a field missing
     * from the detail page is still supplied by the listing.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            subtitle = primary.subtitle ?: fallback.subtitle,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            // A cancellation/relocation/reschedule may be flagged on only one of the pages, so keep
            // whichever page reports a non-default status.
            status = primary.status.takeIf { it != EventStatus.SCHEDULED.name } ?: fallback.status,
            artists = primary.artists.ifEmpty { fallback.artists },
            promoters = primary.promoters.ifEmpty { fallback.promoters }
        )
}

/** Nothing this source withholds needs declaring (#715). */
val COLUMBIA_THEATER_LIMITATIONS = VenueLimitations(EventSource.COLUMBIA_THEATER)
