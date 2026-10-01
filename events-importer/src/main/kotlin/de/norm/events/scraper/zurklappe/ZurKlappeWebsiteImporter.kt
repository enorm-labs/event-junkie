package de.norm.events.scraper.zurklappe

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Zur Klappe's Next.js programme page, a custom site on Vercel.
 *
 * One fetch per import: `/events` carries every upcoming night in its flight payload, parsed by
 * [ZurKlappeOverviewPageScraper]. The detail pages add only a JSON-LD copy of the same fields, so
 * none is fetched. `robots.txt` disallows `/api/`, which the page does not need. Vercel answers
 * `no-store` with no validators, so every import is a full fetch.
 *
 * @see <a href="https://zurklappe.org/events">Zur Klappe events</a>
 */
@Component
class ZurKlappeWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Zur Klappe", ZurKlappeOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ZUR_KLAPPE
}

val ZUR_KLAPPE_LIMITATIONS =
    VenueLimitations(
        EventSource.ZUR_KLAPPE,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the site states one title per night and no second line"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes an opening time, not a separate doors time"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no price for a night"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the party name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no cancelled marker for a night"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the venue sets no cover image; its only image is a generated title card for link previews")
    )
