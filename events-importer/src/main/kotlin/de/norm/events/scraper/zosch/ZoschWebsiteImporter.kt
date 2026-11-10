package de.norm.events.scraper.zosch

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Zosch, a Laravel site whose start page lists the whole programme ([ZoschOverviewPageScraper]).
 * The server sends no `ETag` or `Last-Modified`, so every run reads it.
 *
 * Not read: the `/feed` RSS, which drops the image and the genre, and the `/calendar/<year>/<month>` JSON, which drops
 * the time. The `/show/<date>` links in the feed answer 500.
 *
 * @see <a href="https://www.zosch-berlin.de/">Zosch</a>
 */
@Component
class ZoschWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Zosch", ZoschOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ZOSCH
    override val listsWholeProgramme: Boolean = true
}

val ZOSCH_LIMITATIONS =
    VenueLimitations(
        EventSource.ZOSCH,
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the nights have no working page of their own, so each points at the start page",
            verified = "2026-10-22"
        ),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "a night carries a one-line style and no prose", verified = "2026-10-22"),
        AcceptedLimitation(LimitedAspect.PRICE, "the page prints no price", verified = "2026-10-22"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets; a night links only to the band's website", verified = "2026-10-22"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the page prints one time per night", verified = "2026-10-22"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the page prints no end time", verified = "2026-10-22"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the bar presents every night itself", verified = "2026-10-22"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the page states no ticket status", verified = "2026-10-22"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the page has no status, so a cancelled night can only disappear", verified = "2026-10-22")
    )
