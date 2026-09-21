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
 * Website importer for arkaoda Berlin's hand-coded PHP programme: fetch `?/default/program` via
 * [HtmlFetcher], discover every upcoming event via [ArkaodaOverviewPageScraper], then fetch
 * each `?/default/detail/id=<n>` page via [ArkaodaDetailPageScraper] for the one thing it adds,
 * the untruncated description. Worth a fetch per event here: the listing is a handful of
 * upcoming blocks, and the description is the only place the venue names its lineup, door
 * price or set times. The source URL must point at `?/default/program`; the site root renders
 * no event blocks. Conditional requests are a no-op: neither ETag nor Last-Modified, and
 * `Cache-Control: no-store, no-cache`, so every run is an unconditional GET.
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
     * Merges detail-page data ([primary]) with listing data ([fallback]). The two pages render the
     * same block, so the detail page wins on everything it parsed and the listing fills what it left
     * empty; only the date needs the sentinel check, [UNRESOLVED_EVENT_DATE] being a value.
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
