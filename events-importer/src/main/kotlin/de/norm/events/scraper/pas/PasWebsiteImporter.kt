package de.norm.events.scraper.pas

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for PAS, the Petersburg Art Space in Moabit, over the venue's **The Events Calendar** REST API.
 *
 * The programme is a few events a month, so the walk is one page; [MAX_PAGES] bounds the plugin's `next_rest_url` cursor.
 * The API sends no validators, and the upserts are idempotent by `sourceId`.
 *
 * @see PasApiScraper for the field mapping and the events held elsewhere.
 * @see <a href="https://pas-berlin.org/programme/">PAS programme</a>
 */
@Component
class PasWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "PAS", PasApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.PAS

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val PAS_LIMITATIONS =
    VenueLimitations(
        EventSource.PAS,
        AcceptedLimitation(LimitedAspect.START_TIME, "most concerts print the doors and `music a bit later`, no start"),
        AcceptedLimitation(LimitedAspect.PRICE, "most events set no cost"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "most events set no ticket link of their own"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a series night bills only its name, and its acts are in the prose")
    )
