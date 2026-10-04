package de.norm.events.scraper.cassiopeia

import de.norm.events.event.EventType
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.nextPageUrl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Cassiopeia Berlin's Webflow listing: fetch `/club` via [HtmlFetcher]
 * with conditional headers, parse via [CassiopeiaOverviewPageScraper], fetch each detail page,
 * parse via [CassiopeiaDetailPageScraper], the primary source; the overview supplies discovery
 * and fallback.
 *
 * The listing shows eight events a page. Webflow paginates it server-side with a plain link,
 * `a.w-pagination-next` to `?f74de34a_page=<n>`, which the last page omits, so every page is read
 * (#331).
 *
 * @see CassiopeiaOverviewPageScraper for overview page parsing (discovery + fallback)
 * @see CassiopeiaDetailPageScraper for detail page parsing (primary data source)
 * @see <a href="https://cassiopeia-berlin.de/club">Cassiopeia Club page</a>
 */
@Component
class CassiopeiaWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's past-event cutoff. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher, CassiopeiaOverviewPageScraper(clock)::scrape, CassiopeiaDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.CASSIOPEIA
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.ARTISTS)

    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? = document.nextPageUrl(NEXT_PAGE_SELECTOR)

    /**
     * Fills missing fields in the [primary] detail event from the [fallback] overview event. The
     * detail page is authoritative where it provides a value; the overview contributes on null or a
     * default sentinel (missing genre, "OTHER" type).
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            // The detail page's "OTHER" is a weak signal: a more specific overview type wins over it.
            eventType = primary.eventType?.takeIf { it != EventType.OTHER.name } ?: fallback.eventType
        )

    private companion object {
        const val NEXT_PAGE_SELECTOR = "a.w-pagination-next[href]"
    }
}

val CASSIOPEIA_LIMITATIONS =
    VenueLimitations(
        EventSource.CASSIOPEIA,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no figure on its listing or its event pages")
    )
