package de.norm.events.scraper.privatclub

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Privatclub Berlin's WordPress event listing.
 *
 * One page holds every upcoming event with its details inline, so unlike Cassiopeia
 * there is no detail-page fetch: [HtmlFetcher] fetches `/` conditionally
 * (ETag / Last-Modified), [PrivatclubOverviewPageScraper] parses it. One HTTP
 * request per import cycle.
 *
 * @see PrivatclubOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://privatclub-berlin.de/">Privatclub Berlin</a>
 */
@Component
class PrivatclubWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Privatclub", PrivatclubOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.PRIVATCLUB
    override val listsWholeProgramme: Boolean = true
}

val PRIVATCLUB_LIMITATIONS =
    VenueLimitations(
        EventSource.PRIVATCLUB,
        AcceptedLimitation(LimitedAspect.PRICE, "about half the nights print a genre line and a start time but no price")
    )
