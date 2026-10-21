package de.norm.events.scraper.zbar

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Z Bar, a WordPress.com site with no event post type. The `kulturprogramm`
 * page is typed by hand and lists the whole programme, about six weeks of nights
 * ([ZBarOverviewPageScraper]). WordPress.com sends no `ETag` or `Last-Modified`, so every run reads it.
 *
 * The cinema page (`kino-z-inema`) is not read: it names its films in prose, with no dates.
 *
 * @see <a href="https://zbarberlin.com/kulturprogramm/">Z Bar Kulturprogramm</a>
 */
@Component
class ZBarWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Z Bar programme", ZBarOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.Z_BAR
    override val listsWholeProgramme: Boolean = true
}

val Z_BAR_LIMITATIONS =
    VenueLimitations(
        EventSource.Z_BAR,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the nights have no page of their own, so each points at the programme page"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a night is billed by its title, and its comedians appear only in prose"),
        AcceptedLimitation(LimitedAspect.GENRE, "the bar names no genre"),
        AcceptedLimitation(LimitedAspect.PRICE, "the page prints no price; a night links to an outside ticket shop or says entry is free"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the page prints one time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the page prints no end time"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the page states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the page has no status, so a cancelled night can only disappear")
    )
