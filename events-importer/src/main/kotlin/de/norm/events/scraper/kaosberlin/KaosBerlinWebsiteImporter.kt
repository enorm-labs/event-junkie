package de.norm.events.scraper.kaosberlin

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for KAOS Berlin, the collective's warehouse on the Spree in Oberschöneweide, over the venue's
 * **The Events Calendar** REST API.
 *
 * The programme is a handful of events a month, so the walk is one page; [MAX_PAGES] bounds the plugin's `next_rest_url` cursor.
 * The API sends no validators, and the upserts are idempotent by `sourceId`.
 *
 * @see KaosBerlinApiScraper for the field mapping and the skipped meet-ups.
 * @see <a href="https://kaosberlin.de/veranstaltungen/">KAOS Berlin programme</a>
 */
@Component
class KaosBerlinWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "KAOS Berlin", KaosBerlinApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.KAOS_BERLIN

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val KAOS_BERLIN_LIMITATIONS =
    VenueLimitations(
        EventSource.KAOS_BERLIN,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the API holds one time per event, and the prose rarely names doors"),
        AcceptedLimitation(LimitedAspect.IMAGE, "most events set no featured image"),
        AcceptedLimitation(LimitedAspect.GENRE, "the categories name formats, and the styles are only in the prose"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a party or a series night bills its name, and its acts are in the prose")
    )
