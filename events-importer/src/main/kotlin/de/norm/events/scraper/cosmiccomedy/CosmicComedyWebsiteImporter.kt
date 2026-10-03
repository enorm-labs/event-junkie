package de.norm.events.scraper.cosmiccomedy

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Cosmic Comedy Berlin, the English-language stand-up club on Schönhauser
 * Allee — entirely JSON-sourced over the venue's **The Events Calendar** REST API.
 *
 * The plugin returns the whole upcoming programme (default window from today to two years out)
 * fifty events at a time, handing back its own `next_rest_url` cursor, which this walks rather
 * than building page URLs. [MAX_PAGES] bounds the walk in case the cursor ever fails to terminate.
 *
 * Conditional requests are unused: the window is relative to *today*, so the same URL
 * legitimately yields a different payload each day, and upserts are idempotent by `sourceId`.
 *
 * One venue and one art form, so nothing to filter — everything the API returns is a comedy
 * night on Schönhauser Allee.
 *
 * @see CosmicComedyApiScraper for the parsing logic and the field mapping.
 * @see <a href="https://comedyclubberlin.com/events/">Cosmic Comedy Berlin programme</a>
 */
@Component
class CosmicComedyWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "Cosmic Comedy Berlin", CosmicComedyApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.COSMIC_COMEDY

    private companion object {
        /**
         * Safety bound on the cursor walk. The upcoming programme is two pages today; the cap only
         * stops a runaway if the API ever returns a cursor that does not terminate.
         */
        const val MAX_PAGES = 20
    }
}

val COSMIC_COMEDY_LIMITATIONS =
    VenueLimitations(
        EventSource.COSMIC_COMEDY,
        AcceptedLimitation(LimitedAspect.PRICE, "`cost` and `cost_details` are empty on every event")
    )
