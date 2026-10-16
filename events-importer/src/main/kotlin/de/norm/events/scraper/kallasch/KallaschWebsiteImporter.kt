package de.norm.events.scraper.kallasch

import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock

/**
 * Website importer for the Kallasch&, a WordPress site on Modern Events Calendar. The
 * `/programm/` listing renders six events and loads the rest by AJAX, so the importer reads the
 * plugin's iCalendar feed (`/?mec-ical-feed=1`) instead: every upcoming entry in one response,
 * with its category, description, poster and recurrence. The configured URL stays the listing,
 * the venue's real entry point; the feed is resolved against its host. No conditional request,
 * because the series horizon moves with the clock.
 *
 * @see KallaschCalendarScraper for the parsing.
 * @see <a href="https://kallasch.berlin/programm/">Kallasch& Programm</a>
 */
@Component
class KallaschWebsiteImporter(
    apiClient: ApiClient,
    /** Clock anchoring the series horizon; override in tests. */
    clock: Clock = Clock.system(BERLIN)
) : AbstractJsonApiImporter(apiClient, "Kallasch&", { calendar, _ -> KallaschCalendarScraper(clock).scrape(calendar) }) {
    override val eventSource: EventSource = EventSource.KALLASCH

    override fun requestUrl(url: String): String = URI(url).resolve(FEED_PATH).toString()

    private companion object {
        const val FEED_PATH = "/?mec-ical-feed=1"
    }
}

val KALLASCH_LIMITATIONS =
    VenueLimitations(
        EventSource.KALLASCH,
        AcceptedLimitation(LimitedAspect.GENRE, "the feed files a night by format only, never by style"),
        AcceptedLimitation(LimitedAspect.PRICE, "most nights are free, and a price appears only in the prose"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the feed carries one time per night"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the feed links no ticket shop"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a series names only itself, and its guests appear only in the prose"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the bar programmes its own nights and names no promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the feed marks no night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a cancelled date leaves the feed instead of being marked")
    )
