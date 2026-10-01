package de.norm.events.scraper.monarch

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Monarch Berlin's retro `programm.php` event listing.
 *
 * The whole programme is one hand-coded PHP page with no detail pages, so one request per
 * cycle: [HtmlFetcher] fetches it conditionally (ETag / Last-Modified),
 * [MonarchOverviewPageScraper] parses it.
 *
 * @see MonarchOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://kottimonarch.de/programm.php">Monarch programme</a>
 */
@Component
class MonarchWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Monarch", MonarchOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.MONARCH
    override val listsWholeProgramme: Boolean = true
}

val MONARCH_LIMITATIONS =
    VenueLimitations(
        EventSource.MONARCH,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site is hand-coded PHP with no per-event URLs"),
        AcceptedLimitation(LimitedAspect.PRICE, "the page prints a Ticket Vorverkauf link, never an amount"),
        AcceptedLimitation(LimitedAspect.GENRE, "the page lists only date, title and ticket link; a \"(KONZERT)\" marker is its only classification"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the venue prints no image per night, only one monthly programme poster")
    )
