package de.norm.events.scraper.tresor

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
 * Website importer for Tresor's club programme.
 *
 * Tresor runs WordPress with its REST API disabled site-wide (`401 rest_disabled`) and embeds no
 * structured data, so it is scraped as two HTML pages:
 * 1. Fetches `/club/events/` via [HtmlFetcher] with conditional-request support.
 * 2. Parses the items via [TresorOverviewPageScraper] — the source for the discovery list, date,
 *    title and the floor-grouped lineup.
 * 3. For each event, fetches its `/event/YYYYMMDD-<slug>/` page via [TresorDetailPageScraper] — the
 *    source for the start time (the night's opening set) and the blurb.
 *
 * @see TresorOverviewPageScraper for listing parsing.
 * @see TresorDetailPageScraper for the set times and blurb.
 * @see <a href="https://tresorberlin.com/club/events/">Tresor Berlin</a>
 */
@Component
class TresorWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.TRESOR

    private val overviewPageScraper = TresorOverviewPageScraper()
    private val detailPageScraper = TresorDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges event-page data ([primary]) with listing data ([fallback]).
     *
     * The event page is the only source of the start time and the blurb. The **title** keeps the
     * listing's value, because the event page renders no heading and its document title carries the
     * site name; the lineup prefers the listing's for the same reason — both pages mark it up
     * identically, and the listing is the one the venue curates as the programme.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            title = fallback.title,
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            artists = fallback.artists.ifEmpty { primary.artists }
        )
}

val TRESOR_LIMITATIONS =
    VenueLimitations(
        EventSource.TRESOR,
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "the venue states no doors or start time; the night's opening set is the only clock it gives, and that is stored as the start"
        ),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club states no category; every listing is a club night")
    )
