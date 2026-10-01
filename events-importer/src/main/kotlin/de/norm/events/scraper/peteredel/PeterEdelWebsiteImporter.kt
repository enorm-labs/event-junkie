package de.norm.events.scraper.peteredel

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Kulturhaus Peter Edel's Umbraco programme page.
 *
 * The entire programme is one server-rendered page — 39 events from this month to May 2027 at
 * capture, each with times, prices, promoter credit, flyer and description. No pagination, no
 * per-event page (the title links to the ticket shop), no JSON-LD, no API: an Umbraco grid of
 * hand-authored rich text. One request per cycle: [HtmlFetcher] fetches `/events/` conditionally
 * (ETag / Last-Modified), [PeterEdelOverviewPageScraper] parses it.
 *
 * The server sends `Cache-Control: private` and neither validator header, so the conditional
 * request never saves a fetch. The [FetchResult.NotModified] branch stays — it costs nothing and
 * covers the server growing an `ETag` later.
 *
 * @see PeterEdelOverviewPageScraper for the HTML parsing logic and the source's limitations.
 * @see <a href="https://www.peteredel.de/events/">Kulturhaus Peter Edel</a>
 */
@Component
class PeterEdelWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Kulturhaus Peter Edel", PeterEdelOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.PETER_EDEL
    override val listsWholeProgramme: Boolean = true
}

val PETER_EDEL_LIMITATIONS =
    VenueLimitations(
        EventSource.PETER_EDEL,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue publishes no event category at all, across a programme spanning concerts, comedy, readings and dance teas"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "without a category nothing confirms that a title is a performer rather than a format, so an act is taken only when a support act is billed"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the title links straight to the ticket shop")
    )
