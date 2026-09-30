package de.norm.events.scraper.hole44

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
 * Website importer for Hole 44 Berlin's Events-Manager concert listing.
 *
 * WordPress/Events-Manager whose REST API is not exposed for anonymous reads, so two HTML pages:
 * 1. [HtmlFetcher] fetches `/events/` conditionally (ETag / Last-Modified).
 * 2. [Hole44OverviewPageScraper] parses the items — discovery list, date, start time, genre,
 * status, and headliner/support artists.
 * 3. Each detail page via [Hole44DetailPageScraper] — description, image, promoter and doors time.
 *
 * @see Hole44OverviewPageScraper for overview parsing (date, status, artists, fallback).
 * @see Hole44DetailPageScraper for detail parsing (description, image, promoter, doors).
 * @see <a href="https://hole-berlin.de/events/">Hole 44 Berlin</a>
 */
@Component
class Hole44WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.HOLE44
    override val listsWholeProgramme: Boolean = true

    private val overviewPageScraper = Hole44OverviewPageScraper()
    private val detailPageScraper = Hole44DetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)
}

val HOLE44_LIMITATIONS =
    VenueLimitations(
        EventSource.HOLE44,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no figure; tickets are sold through an Eventim button")
    )
