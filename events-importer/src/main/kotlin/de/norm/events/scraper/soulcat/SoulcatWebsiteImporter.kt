package de.norm.events.scraper.soulcat

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Soulcat, the vinyl-only music bar on Pannierstraße, over the venue's
 * **The Events Calendar** REST API. The plugin returns about a month of nights, fifty a page.
 *
 * @see SoulcatApiScraper for the parsing logic and the field mapping.
 * @see <a href="https://soulcat-berlin.com/">Soulcat programme</a>
 */
@Component
class SoulcatWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "Soulcat", SoulcatApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.SOULCAT

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val SOULCAT_LIMITATIONS =
    VenueLimitations(
        EventSource.SOULCAT,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "every event carries a title and times only"),
        AcceptedLimitation(LimitedAspect.IMAGE, "no event has an image"),
        AcceptedLimitation(LimitedAspect.PRICE, "`cost` is empty on every event; only `FREE ENTRY` in a title says anything"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "a bar with no tickets; `website` is empty"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the house nights (`Bartenders Choice`) name no DJ"),
        houseGenre = "Soul"
    )
