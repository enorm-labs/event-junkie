package de.norm.events.scraper.morphine

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
 * Website importer for Morphine Raum, the Kreuzberg concert room of the Morphine Records label.
 *
 * The site is hand-coded on Kirby and fully server-rendered — no schema.org JSON-LD, no REST or
 * GraphQL endpoint, and no third-party calendar widget behind it, so it is read as two HTML
 * pages:
 * 1. Fetches the `/events` listing via [HtmlFetcher] with conditional-request support. The venue
 *    sits behind Varnish and currently returns neither `ETag` nor `Last-Modified`, so the
 *    conditional request is a no-op in practice and every run re-reads the page; that costs one
 *    request and keeps the importer correct if the headers ever appear.
 * 2. Parses the listing rows via [MorphineOverviewPageScraper] — the discovery list, plus the
 *    date and title that stand in when a detail fetch fails.
 * 3. For each event, fetches and parses its `/events/<slug>` page via
 *    [MorphineDetailPageScraper] — the source for the door and start times, the lineup, the
 *    description, the image and the pricing.
 *
 * The whole upcoming programme is on that one listing page — around a dozen nights running two
 * months out — so there is nothing to paginate and no month pages to walk.
 *
 * @see MorphineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see MorphineDetailPageScraper for detail parsing (times, lineup, description, image, prices).
 * @see <a href="http://www.morphinerecords.com/events">Morphine Raum event listing</a>
 */
@Component
class MorphineWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.MORPHINE

    private val overviewPageScraper = MorphineOverviewPageScraper()
    private val detailPageScraper = MorphineDetailPageScraper()

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
     * The detail page is authoritative: it carries every field the listing lacks, and states the
     * same date in the same spelling. Only the date and the title-derived artists fall back to the
     * overview — the date because a detail page whose `.block.day` header cannot be parsed would
     * otherwise be dropped despite the listing knowing when the night is, and the artists because
     * a page with no `ul.lineup` still has a billed act in its title.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

val MORPHINE_LIMITATIONS =
    VenueLimitations(
        EventSource.MORPHINE,
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue flags nothing sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a dropped night is removed from the listing rather than flagged"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the advance-sale button posts to PayPal rather than linking anywhere"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "nearly every night is priced as a sliding scale or donation range, which the model has no field for, so the wording is kept verbatim as the note"
        )
    )
