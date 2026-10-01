package de.norm.events.scraper.gartn

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for gART.n's single-page Carrd programme.
 *
 * The whole web presence is one Carrd page — no CMS, REST API, feed or structured data, and a
 * `sitemap.xml` listing only that page and the separate Impressum card. Its "UPCOMING" block
 * carries the entire programme inline with every lineup and ticket link, so a single fetch:
 * [HtmlFetcher] fetches it conditionally — Carrd's Apache serves a strong `ETag` and a
 * `Last-Modified`, and the page changes only when the programme is edited, so 304s are
 * reliable and frequent — and [GartnOverviewPageScraper] parses every dated block.
 *
 * @see GartnOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.gartn.xyz/">gART.n Berlin</a>
 */
@Component
class GartnWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "gART.n", GartnOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.GARTN
    override val listsWholeProgramme: Boolean = true
}

val GARTN_LIMITATIONS =
    VenueLimitations(
        EventSource.GARTN,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes no prices"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre; every night takes the club's Techno default"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the venue publishes no per-event image"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the venue publishes no per-event text"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the Carrd page emits no per-event URL, and removes an event once it has passed"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue states one time per night and no separate doors time"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue states no category; every night here is a DJ party"),
        houseGenre = "Techno"
    )
