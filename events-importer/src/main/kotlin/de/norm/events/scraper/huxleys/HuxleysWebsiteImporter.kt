package de.norm.events.scraper.huxleys

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Huxleys Neue Welt's WordPress/Events-Manager concert listing.
 *
 * The Events-Manager `event` post type is not on the WordPress REST API (only stock post types
 * are registered) and the theme embeds no JSON-LD, so two HTML pages:
 * 1. [HtmlFetcher] fetches `/events` conditionally.
 * 2. [HuxleysOverviewPageScraper] parses the cards — discovery list, date, title, times, status,
 * sold-out flag and support acts.
 * 3. Each detail page via [HuxleysDetailPageScraper] — tour name, poster, ticket URL,
 * description, genre and promoter.
 *
 * @see HuxleysOverviewPageScraper for overview parsing (discovery, date, times, status, fallback).
 * @see HuxleysDetailPageScraper for detail parsing (tour name, image, tickets, genre, promoter).
 * @see <a href="https://huxleysneuewelt.de/events">Huxleys Neue Welt</a>
 */
@Component
class HuxleysWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.HUXLEYS

    private val overviewPageScraper = HuxleysOverviewPageScraper()
    private val detailPageScraper = HuxleysDetailPageScraper()

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
     * authoritative for what the overview lacks (tour name, image, ticket URL, description, genre,
     * promoter); shared fields (date, times, sold-out) prefer it and fall back to the listing.
     *
     * Two fields keep the **overview's** value:
     * - **`title`**: the detail page renders no heading — only the document title with the site
     * name appended — so the listing's `.eventname` is cleaner (and why [HuxleysDetailPageScraper]
     * derives one only to stand alone).
     * - **`status`**, whenever the overview found a non-default one: a relocation or new date is
     * announced solely in the listing's `.anderungen` note, which the detail page omits.
     *
     * `subtitle` combines both: tour name from the detail page, support acts from the listing, so a
     * support line is never lost to a show with a tour title.
     *
     * **`artists` is derived afresh from that merged pair, not picked from one page.** Neither page
     * holds both halves: the act's name is the listing's `.eventname`, the `.tourtitel` is the
     * detail page's alone — each scraper builds its lineup from half the evidence, and picking a
     * winner keeps whichever half is wrong. `Corrupted Blood Club Show` forced it: the listing sees
     * a bare concert title and mints the night's name as a performer; only the detail page says
     * `Corrupted Blood Records presents`, which identifies a label showcase with no act in the title
     * (`headlinersFromTitle`). Rebuilding also removes the old blind spot where a listing support
     * line could be overridden by the detail lineup or vice versa.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent {
        val subtitle = listOfNotNull(primary.subtitle, fallback.subtitle).distinct().joinToString(SUBTITLE_SEPARATOR).takeIf { it.isNotBlank() }
        return primary.copy(
            title = fallback.title,
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            subtitle = subtitle,
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime,
            soldOut = primary.soldOut || fallback.soldOut,
            status = fallback.status.takeIf { it != EventStatus.SCHEDULED.name } ?: primary.status,
            statusNote = fallback.statusNote ?: primary.statusNote,
            // Built from the fields this merge stores — the listing's title and the joined subtitle — so
            // the lineup can never describe a title or type the row does not carry.
            artists = buildArtistsForEventType(fallback.title, subtitle, primary.eventType)
        )
    }

    private companion object {
        /** Separator joining the detail page's tour name to the listing's support line. */
        const val SUBTITLE_SEPARATOR = " | "
    }
}

val HUXLEYS_LIMITATIONS =
    VenueLimitations(
        EventSource.HUXLEYS,
        AcceptedLimitation(LimitedAspect.PRICE, "most shows sell through Eventim and print no price at all — one of eleven sampled pages carried one")
    )
