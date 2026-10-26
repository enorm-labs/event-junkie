package de.norm.events.scraper.donau115

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Donau115. The homepage renders its programme in the browser from a public Firebase Realtime
 * Database, so the importer reads the same `events.json` the page script fetches: the whole programme and its archive
 * in one response. The database refuses a date query without an index, so the past is dropped in the scraper. The
 * configured URL stays the homepage, the venue's entry point and every event's `sourceUrl`.
 *
 * @see Donau115ApiScraper for the parsing.
 * @see <a href="https://www.donau115.de/">Donau115</a>
 */
@Component
class Donau115WebsiteImporter(
    apiClient: ApiClient,
    /** Clock for the past-night cutoff; override in tests. */
    clock: Clock = Clock.system(BERLIN)
) : AbstractJsonApiImporter(apiClient, "Donau115", Donau115ApiScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.DONAU115
    override val listsWholeProgramme: Boolean = true

    override fun requestUrl(url: String): String = EVENTS_URL

    private companion object {
        const val EVENTS_URL = "https://shifts-a77a1-default-rtdb.europe-west1.firebasedatabase.app/events.json"
    }
}

val DONAU115_LIMITATIONS =
    VenueLimitations(
        EventSource.DONAU115,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the programme is one page that expands each night in place"),
        AcceptedLimitation(LimitedAspect.START_TIME, "most nights carry only the bar's opening time, and a set time appears only in the prose"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets, and a night's links go to the acts"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the weekly open stage and jam name no act"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the bar books its own nights and names no promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site marks no night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a cancelled night is unpublished instead of being marked"),
        houseGenre = "Jazz"
    )
