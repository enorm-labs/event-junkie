package de.norm.events.scraper.claerchensballhaus

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.PLAIN_SCRAPER_USER_AGENT
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Clärchens Ballhaus' WordPress programme, one request per run: `/programm/` carries the whole
 * calendar, past and future, in its script.
 *
 * The site's WAF answers 403 to a `User-Agent` with the `(compatible; …)` frame, so `app.scraper.plain-user-agent-hosts`
 * names the host: the listing, the posters and `robots.txt` go out under [PLAIN_SCRAPER_USER_AGENT]. The file blocks only
 * WordPress internals; the posters sit under `/wp-content/uploads/`, which it allows.
 */
@Component
class ClaerchensBallhausWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Clärchens Ballhaus", ClaerchensBallhausCalendarScraper()::scrape) {
    override val eventSource: EventSource = EventSource.CLAERCHENS_BALLHAUS
    override val listsWholeProgramme: Boolean = true
}

val CLAERCHENS_BALLHAUS_LIMITATIONS =
    VenueLimitations(
        EventSource.CLAERCHENS_BALLHAUS,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the calendar names no category, so the type comes from the words under the title",
            verified = "2026-10-23"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the dance nights and the house's concert series name nobody; only a guest concert's title bills its act",
            verified = "2026-10-23"
        ),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the calendar prints a price for the dance classes only, and the concerts sell through outside shops",
            verified = "2026-10-23"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names a dance, not a music style", verified = "2026-10-23"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "only the Sunday concerts print an `Einlass`, as free text", verified = "2026-10-23"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the calendar's end is midnight of the first occurrence, not the night's end", verified = "2026-10-23"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the calendar names no promoter", verified = "2026-10-23")
    )
