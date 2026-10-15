package de.norm.events.scraper.yaam

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for YAAM, the socio-cultural centre at Schillingbrücke, over the venue's **The Events Calendar**
 * REST API. The `/programm/` page is a Divi calendar widget over the same plugin.
 *
 * @see YaamApiScraper for the field mapping.
 * @see <a href="https://yaam.de/programm/">YAAM programme</a>
 */
@Component
class YaamWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "YAAM", YaamApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.YAAM

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val YAAM_LIMITATIONS =
    VenueLimitations(
        EventSource.YAAM,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "an event carries one start time only"),
        AcceptedLimitation(LimitedAspect.GENRE, "no event sets a category; the style is only in the prose"),
        AcceptedLimitation(LimitedAspect.PRICE, "`cost` is empty on every event"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "`website` is empty on every event"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a jam session names only its host series, and no act"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "every event names the venue itself as organizer")
    )
