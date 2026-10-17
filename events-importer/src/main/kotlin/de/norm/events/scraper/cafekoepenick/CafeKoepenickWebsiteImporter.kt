package de.norm.events.scraper.cafekoepenick

import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Café Köpenick youth club. Its site, hdjk.de, links a kalender.digital
 * calendar, an Angular app. The app's sub-calendar "öffentliche Veranstaltungen/ Angebote" has an
 * iCalendar export, and the configured URL is that feed. `past_months=0&future_months=12` bounds
 * it to the coming year: the weekly entries are published three years ahead. The club's
 * radar.squat.net group (node 8188) lists only the open decks and the political meetings.
 *
 * @see CafeKoepenickCalendarScraper for the parsing.
 * @see <a href="https://hdjk.de/veranstaltungen-events/">Café Köpenick Veranstaltungen</a>
 */
@Component
class CafeKoepenickWebsiteImporter(
    apiClient: ApiClient
) : AbstractJsonApiImporter(apiClient, "Café Köpenick", CafeKoepenickCalendarScraper()::scrape) {
    override val eventSource: EventSource = EventSource.CAFE_KOEPENICK
}

val CAFE_KOEPENICK_LIMITATIONS =
    VenueLimitations(
        EventSource.CAFE_KOEPENICK,
        AcceptedLimitation(LimitedAspect.START_TIME, "most band nights are all-day calendar entries with no time"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar gives at most one time per night"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the calendar notes are the staff's own, not a text for visitors"),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names no style"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the calendar carries no poster"),
        AcceptedLimitation(LimitedAspect.PRICE, "the calendar names no entry price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the calendar links no ticket shop"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the calendar names no promoter"),
        AcceptedLimitation(LimitedAspect.END_TIME, "an all-day calendar entry has no end time"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a band night bills its acts only in the title, and some titles say only bands"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the calendar has no page per event, so every event links the calendar")
    )
