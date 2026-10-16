package de.norm.events.scraper.konradtoenz

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Konrad Tönz, a one-page site builder (sitehub "webcard") with no API. The home
 * page's events block lists the whole programme, about six weeks of nights ([KonradToenzOverviewPageScraper]).
 *
 * The source URL is plain `http`: the `https` host serves a self-signed certificate. `robots.txt` redirects
 * there, so the robots check logs a `WARN` and allows the fetch; the file disallows only `/impressum`.
 * The bar holds one night a date and the site has no page per night, so the date is the night's identity.
 *
 * @see <a href="http://www.konradtoenzbar.de/#events">Konrad Tönz events</a>
 */
@Component
class KonradToenzWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Konrad Tönz events", KonradToenzOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.KONRAD_TOENZ
    override val listsWholeProgramme: Boolean = true
}

val KONRAD_TOENZ_LIMITATIONS =
    VenueLimitations(
        EventSource.KONRAD_TOENZ,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the page prints a name and a style line per night, no text about it"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a record night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the events block carries no pictures"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets and links no ticket shop"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the home page")
    )
