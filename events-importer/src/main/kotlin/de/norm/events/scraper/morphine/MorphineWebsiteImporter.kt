package de.norm.events.scraper.morphine

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Morphine Raum, the Kreuzberg concert room of the Morphine Records label.
 *
 * Hand-coded on Kirby, fully server-rendered — no JSON-LD, no REST or GraphQL, no third-party
 * calendar widget — so two HTML pages:
 * 1. [HtmlFetcher] fetches `/events` conditionally. The venue sits behind Varnish and returns
 * neither `ETag` nor `Last-Modified`, so the conditional request is a no-op and every run
 * re-reads the page; one request, and correct if the headers ever appear.
 * 2. [MorphineOverviewPageScraper] parses the rows — discovery list, plus the date and title
 * that stand in when a detail fetch fails.
 * 3. Each `/events/<slug>` page via [MorphineDetailPageScraper] — door and start times, lineup,
 * description, image and pricing.
 *
 * The whole programme is on that one listing — around a dozen nights running two months out —
 * so nothing to paginate and no month pages to walk.
 *
 * @see MorphineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see MorphineDetailPageScraper for detail parsing (times, lineup, description, image, prices).
 * @see <a href="http://www.morphinerecords.com/events">Morphine Raum event listing</a>
 */
@Component
class MorphineWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, MorphineOverviewPageScraper()::scrape, MorphineDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.MORPHINE
    override val listsWholeProgramme: Boolean = true
}

val MORPHINE_LIMITATIONS =
    VenueLimitations(
        EventSource.MORPHINE,
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue flags nothing sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a dropped night is removed from the listing rather than flagged"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the advance-sale button posts to PayPal rather than linking anywhere"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "nearly every night is priced as a sliding scale or donation range, which the model has no field for, so the wording is kept verbatim as the note"
        )
    )
