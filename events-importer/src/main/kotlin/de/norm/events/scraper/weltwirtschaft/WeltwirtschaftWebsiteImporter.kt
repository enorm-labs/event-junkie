package de.norm.events.scraper.weltwirtschaft

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Weltwirtschaft, the restaurant and beer garden of the Haus der Kulturen der Welt, over its
 * **The Events Calendar** REST API. The calendar mixes a monthly DJ night and talks with closure notices; the scraper drops the notices.
 *
 * @see WeltwirtschaftApiScraper for the notice filter and the field mapping.
 * @see <a href="https://weltwirtschaft.berlin/">Weltwirtschaft</a>
 */
@Component
class WeltwirtschaftWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "Weltwirtschaft", WeltwirtschaftApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.WELTWIRTSCHAFT

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val WELTWIRTSCHAFT_LIMITATIONS =
    VenueLimitations(
        EventSource.WELTWIRTSCHAFT,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar gives one time per event"),
        AcceptedLimitation(LimitedAspect.PRICE, "`cost` is empty on nearly every event, and the description rarely names a price"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the resident DJ of the monthly night is named only in the description prose"),
        AcceptedLimitation(LimitedAspect.GENRE, "no event names a style; the DJ nights run across genres"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the house presents its nights itself and names no organizer")
    )
