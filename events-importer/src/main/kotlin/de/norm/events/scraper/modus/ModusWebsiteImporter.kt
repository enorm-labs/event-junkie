package de.norm.events.scraper.modus

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Modus Berlin's programme.
 *
 * 1. [HtmlFetcher] fetches the unpaginated `/events` page conditionally (ETag / Last-Modified).
 * 2. [ModusOverviewPageScraper] discovers every `.event-item` tile — title, rendered date and poster.
 * 3. Each `/event/DDMMYY-<Name>` page via [ModusDetailPageScraper] — start time, ticket link
 * and description.
 *
 * @see ModusOverviewPageScraper for listing parsing (discovery, date, poster).
 * @see ModusDetailPageScraper for detail parsing (times, ticket, description).
 * @see <a href="https://modus-berlin.de/events">Modus event listing</a>
 */
@Component
class ModusWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, ModusOverviewPageScraper()::scrape, ModusDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.MODUS
    override val listsWholeProgramme: Boolean = true
}

/** Nothing this source withholds needs declaring (#715). */
val MODUS_LIMITATIONS = VenueLimitations(EventSource.MODUS)
