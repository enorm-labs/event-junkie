package de.norm.events.scraper.binuu

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Bi Nuu's SvelteKit/PocketBase listing: fetch `/de/events` via
 * [HtmlFetcher] with conditional headers, discover events from `data.events[]` via
 * [BinuuOverviewPageScraper], then fetch each detail page and parse `data.item` via
 * [BinuuDetailPageScraper], a superset of the overview entry, so the merge prefers it and uses
 * the overview as a safety net.
 *
 * @see BinuuOverviewPageScraper for overview parsing (discovery, fallback)
 * @see BinuuDetailPageScraper for detail parsing (doors, description, tickets, promoters, artists)
 * @see <a href="https://binuu.de/de/events">Bi Nuu event listing</a>
 */
@Component
class BinuuWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, BinuuOverviewPageScraper()::scrape, BinuuDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.BINUU
    override val listsWholeProgramme: Boolean = true
}

val BINUU_LIMITATIONS =
    VenueLimitations(
        EventSource.BINUU,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the SvelteKit payload carries no category field, and neither does anywhere else on the site"),
        AcceptedLimitation(LimitedAspect.PRICE, "the payload carries no price field and the pages print no figure; tickets are sold through outside shops"),
        AcceptedLimitation(LimitedAspect.GENRE, "the payload and JSON-LD carry no genre; style appears only in the description")
    )
