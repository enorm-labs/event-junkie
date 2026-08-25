package de.norm.events.scraper.arkaoda

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
 * Website importer for arkaoda Berlin's hand-coded PHP programme.
 *
 * Follows the overview → detail pattern:
 * 1. Fetch the `?/default/program` listing via [HtmlFetcher] and discover every
 *    upcoming event via [ArkaodaOverviewPageScraper] — date, category-derived type,
 *    title, flyer, promoter and artists, plus the `?/default/detail/id=<n>` link.
 * 2. For each event, fetch that detail page and parse it via
 *    [ArkaodaDetailPageScraper], whose one addition is the **untruncated
 *    description**; the listing body is cut off mid-sentence.
 *
 * Fetching a detail page for a single extra field is worth it here where it would not
 * be on a large listing: arkaoda shows only its *upcoming* events, so the listing is a
 * handful of blocks rather than a full season, and the description is the only place
 * the venue names its lineup, door price or set times in any form.
 *
 * The configured source URL must point at `?/default/program` — the site root renders
 * the same theme but no event blocks.
 *
 * Conditional requests are a no-op against this server: it sends neither ETag nor
 * Last-Modified and answers `Cache-Control: no-store, no-cache`, so nothing is ever
 * cached to send back and every run is an unconditional GET. Re-imports stay cheap
 * because the listing is short and idempotent `sourceId` upserts skip unchanged rows.
 *
 * @see ArkaodaOverviewPageScraper for discovery + the venue's published fields.
 * @see ArkaodaDetailPageScraper for the untruncated description.
 * @see ArkaodaFieldMapping for the field rules the two scrapers share.
 * @see <a href="https://berlin.arkaoda.com/?/default/program">arkaoda Berlin programme</a>
 */
@Component
class ArkaodaWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.ARKAODA

    private val overviewPageScraper = ArkaodaOverviewPageScraper()
    private val detailPageScraper = ArkaodaDetailPageScraper()

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
     * The two pages render the same event block, so this is a plain fallback chain
     * rather than a per-field authority split: the detail page wins on everything it
     * parsed — it is the newer read and the only one with the full description — and
     * the listing fills whatever it left empty. Only the date needs the sentinel check,
     * since [UNRESOLVED_EVENT_DATE] is a value rather than a null.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            eventType = primary.eventType ?: fallback.eventType,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            artists = primary.artists.ifEmpty { fallback.artists },
            promoters = primary.promoters.ifEmpty { fallback.promoters }
        )
}

val ARKAODA_LIMITATIONS =
    VenueLimitations(
        EventSource.ARKAODA,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "a set time is written into the prose blurb, which has no reliable delimiter"),
        AcceptedLimitation(LimitedAspect.START_TIME, "a set time is written into the prose blurb, which has no reliable delimiter"),
        AcceptedLimitation(LimitedAspect.PRICE, "a door price is written into the prose blurb, which has no reliable delimiter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue runs no ticket integration and has no field for the sold-out state"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue has no structured genre field")
    )
