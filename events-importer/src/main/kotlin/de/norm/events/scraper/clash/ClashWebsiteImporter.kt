package de.norm.events.scraper.clash

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Clash Berlin's WordPress-based event listing.
 *
 * All upcoming events render inline in the homepage `#events` section — no detail pages, so a
 * single request: [HtmlFetcher] fetches the homepage conditionally (ETag / Last-Modified),
 * [ClashOverviewPageScraper] parses it.
 *
 * @see ClashOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://clash-berlin.de/">Clash Berlin</a>
 */
@Component
class ClashWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Clash", ClashOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.CLASH
    override val listsWholeProgramme: Boolean = true
}

val CLASH_LIMITATIONS =
    VenueLimitations(
        EventSource.CLASH,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the `event` post type is not exposed over the WordPress REST API and the numeric permalinks 404"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the homepage listing is the whole source and carries no doors time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the homepage listing is the whole source and carries no price"),
        AcceptedLimitation(LimitedAspect.GENRE, "the homepage listing is the whole source and carries no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the homepage listing is the whole source and names no promoter"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site has no category field; the type is inferred from the title, defaulting to a concert")
    )
