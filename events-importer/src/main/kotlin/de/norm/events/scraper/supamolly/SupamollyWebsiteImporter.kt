package de.norm.events.scraper.supamolly

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Supamolly Berlin's retro hand-coded PHP programme.
 *
 * The whole programme is one server-rendered page (`?p=programm`, identical to the homepage),
 * so one request per cycle: [HtmlFetcher] fetches it conditionally (ETag / Last-Modified — the
 * server sends neither, so every cycle is a full fetch; the idempotent `sourceId` upsert
 * absorbs that), [SupamollyOverviewPageScraper] parses it.
 *
 * No detail pages: `index.php?programm=<stamp>` serves the full-size flyer JPEG, not HTML, and
 * the advertised `rss.php` feed is unusable (item titles render the date as `"Mi 9.2026..09."`,
 * dropping the day of month).
 *
 * @see SupamollyOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.supamolly.de/?p=programm">Supamolly Berlin</a>
 */
@Component
class SupamollyWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Supamolly", SupamollyOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SUPAMOLLY
}

val SUPAMOLLY_LIMITATIONS =
    VenueLimitations(
        EventSource.SUPAMOLLY,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes no prices"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the venue runs no ticket shop")
    )
