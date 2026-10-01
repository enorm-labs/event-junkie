package de.norm.events.scraper.saalchen

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Säälchen's programme on the Holzmarkt site's shared calendar.
 *
 * The whole programme is one `/kalender` page — month tabs are in-page anchors — and every
 * field is on it, so a single fetch: [HtmlFetcher] fetches it conditionally (ETag /
 * Last-Modified), [SaalchenOverviewPageScraper] filters to the venue and parses. The
 * `/veranstaltung/<slug>` detail pages are not fetched: the embedded AddToCalendar payload
 * already carries the prose, times and price.
 *
 * @see SaalchenOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.holzmarkt.com/kalender">Holzmarkt calendar</a>
 */
@Component
class SaalchenWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Säälchen", SaalchenOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SAALCHEN
    override val listsWholeProgramme: Boolean = true
}

val SAALCHEN_LIMITATIONS =
    VenueLimitations(
        EventSource.SAALCHEN,
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre field of its own")
    )
