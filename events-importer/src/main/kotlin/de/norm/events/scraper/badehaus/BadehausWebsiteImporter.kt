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
 * Overview → detail:
 * 1. [HtmlFetcher] fetches `/events/` (ETag / Last-Modified); [BadehausOverviewPageScraper]
 * discovers every event — authoritative for the sold-out flag (a CSS class on the card), the
 * subtitle and the inferred event type, plus fallback title/date/doors/image.
 * 2. Each `/events/<slug>/` detail page via [BadehausDetailPageScraper] — primary for the full
 * description, the start time (`Beginn`) and the promoter, which the card omits.
 *
 * The configured source URL must point at `/events/` (the homepage is a separate landing page).
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
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page is
     * authoritative for what only it carries — description, start time, promoter and the status its
     * notice announces. The overview is authoritative for the sold-out flag (the card's CSS class),
     * the subtitle and the inferred type; the rest, the status among them, falls back to the
     * overview only when the detail page did not supply it.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            // The detail page carries the real date; fall back only if absent (sentinel).
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            // Overview-only fields (the detail scraper leaves these unset).
            subtitle = primary.subtitle ?: fallback.subtitle,
            eventType = primary.eventType ?: fallback.eventType,
            // Artists come from the overview (title + subtitle + type); the detail page has no roster.
            artists = primary.artists.ifEmpty { fallback.artists },
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            ticketUrl = primary.ticketUrl ?: fallback.ticketUrl,
            soldOut = primary.soldOut || fallback.soldOut,
            // The card's one VERLEGT class covers a date move and a house move alike; the detail page's
            // notice tells them apart, so it wins and the class is the fallback (#1578).
            status = primary.status.takeIf { it != EventStatus.SCHEDULED.name } ?: fallback.status,
            statusNote = primary.statusNote ?: fallback.statusNote
        )
}

val BADEHAUS_LIMITATIONS =
    VenueLimitations(
        EventSource.BADEHAUS,
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the venue publishes no roster; for a concert the title is taken as the act and a Support: subtitle as the rest"
        ),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue publishes no category; the type is inferred from the title and subtitle"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the venue prints no figure, and where it names money at all it is a donation range the model has no field for, kept verbatim as the note"
        )
    )
