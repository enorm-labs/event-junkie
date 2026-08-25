package de.norm.events.scraper.badehaus

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
 * Website importer for Badehaus Berlin's WordPress `/events/` programme.
 *
 * Follows the overview → detail pattern:
 * 1. Fetch the `/events/` listing via [HtmlFetcher] (with ETag / Last-Modified) and
 *    discover every event via [BadehausOverviewPageScraper] — the authoritative
 *    source for the sold-out / relocated status (a CSS class on the card), the
 *    subtitle and the inferred event type, plus fallback title/date/doors/image.
 * 2. For each event, fetch its `/events/<slug>/` detail page and parse it via
 *    [BadehausDetailPageScraper] — the primary source for the full description,
 *    the start time (`Beginn`) and the promoter, which the listing card omits.
 *
 * The configured source URL must point at `/events/` (the homepage is a separate
 * landing page).
 *
 * @see BadehausOverviewPageScraper for discovery + the authoritative fields.
 * @see BadehausDetailPageScraper for the enriched per-event fields.
 * @see <a href="https://badehaus-berlin.com/events/">Badehaus Berlin programme</a>
 */
@Component
class BadehausWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.BADEHAUS

    private val overviewPageScraper = BadehausOverviewPageScraper()
    private val detailPageScraper = BadehausDetailPageScraper()

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
     * The detail page is authoritative for the fields only it carries — description,
     * start time and promoter. The overview page is authoritative for the sold-out /
     * relocated status (from the listing card's CSS class), the subtitle and the
     * inferred event type; the remaining fields fall back to the overview only when
     * the detail page didn't supply them.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            // The detail page carries the real date; fall back only if it was absent (sentinel).
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            // Overview-only fields (the detail scraper leaves these unset).
            subtitle = primary.subtitle ?: fallback.subtitle,
            eventType = primary.eventType ?: fallback.eventType,
            // Artists are extracted from the overview (title + subtitle + type); the
            // detail page carries no roster, so it falls back to the overview.
            artists = primary.artists.ifEmpty { fallback.artists },
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            // Status + sold-out are only reliable on the overview card, so it wins.
            soldOut = primary.soldOut || fallback.soldOut,
            status = fallback.status.takeIf { it != EventStatus.SCHEDULED.name } ?: primary.status
        )
}

val BADEHAUS_LIMITATIONS =
    VenueLimitations(
        EventSource.BADEHAUS,
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the venue publishes no roster; for a concert the title is taken as the act and a Support: subtitle as the rest"
        ),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue publishes no category; the type is inferred from the title and subtitle")
    )
