package de.norm.events.scraper.weekend

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Weekend's Squarespace `/upcoming` page.
 *
 * The list carries every upcoming night with its whole record, so no event page is fetched
 * ([WeekendUpcomingPageScraper]). `robots.txt` disallows the collection's `?format=json`, so the HTML is the source.
 *
 * @see <a href="https://www.weekendclub.berlin/upcoming">Weekend programme</a>
 */
@Component
class WeekendWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Weekend", WeekendUpcomingPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.WEEKEND
    override val listsWholeProgramme: Boolean = true
}

val WEEKEND_LIMITATIONS =
    VenueLimitations(
        EventSource.WEEKEND,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the listing prints a title and a blurb, no second title line", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a club night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the listing prints a start and an end time and no doors time", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.GENRE, "the listing names no style field, only the blurb describes one", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PRICE, "the prices are only in the embedded ticket shop", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the guest crew is named only in the title, as `WKND X <crew>`", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the listing states no ticket status", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the listing has no status field, so a cancelled night can only disappear", verified = "2026-10-17")
    )
