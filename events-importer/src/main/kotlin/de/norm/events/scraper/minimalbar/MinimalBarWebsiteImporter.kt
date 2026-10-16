package de.norm.events.scraper.minimalbar

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Minimal Bar's own site, a statically exported Next.js app.
 *
 * One fetch per import: `/dates/` carries the whole standing programme, more than a year ahead,
 * parsed by [MinimalBarOverviewPageScraper]. Each night's page at `/dates/<date>/` repeats the
 * listing's fields and the house text, so it is never fetched. robots.txt disallows `/dates/view`
 * and `/archive`, neither of which this reads.
 *
 * @see <a href="https://minimal.berlin/dates/">Minimal Bar events</a>
 */
@Component
class MinimalBarWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Minimal Bar", MinimalBarOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.MINIMAL_BAR
    override val listsWholeProgramme: Boolean = true
}

val MINIMAL_BAR_LIMITATIONS =
    VenueLimitations(
        EventSource.MINIMAL_BAR,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the site names the night's series and its lineup, no second line"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "every night carries the same text about the bar, never one about the night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the bar opens at 18:00 every day and the site gives only the night's start"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site gives the bar's closing hours, not an end per night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site states one sound for every night; each night takes it"),
        AcceptedLimitation(LimitedAspect.PRICE, "entrance is free every night, so the site prints no price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entrance is free and the venue sells no tickets"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the series name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the payload has no status field, so a cancelled night can only disappear"),
        houseGenre = "Minimal, House, Tech House"
    )
