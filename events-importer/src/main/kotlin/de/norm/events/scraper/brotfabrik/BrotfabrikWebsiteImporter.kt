package de.norm.events.scraper.brotfabrik

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Brotfabrik, the arts centre at the Weißenseer Spitze, over the house's **The Events Calendar**
 * REST API.
 *
 * The source URL asks the API for the `buehne` and `literatur` categories only, one page where the whole programme fills
 * nine, most of it cinema and courses; the scraper drops any other category again. The walk follows the plugin's
 * `next_rest_url` cursor, and [MAX_PAGES] bounds it. Conditional requests are unused, because the window moves with today
 * and upserts are idempotent by `sourceId`.
 *
 * @see BrotfabrikApiScraper for the field mapping.
 * @see <a href="https://brotfabrik-berlin.de/veranstaltungen/">Brotfabrik programme</a>
 */
@Component
class BrotfabrikWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "Brotfabrik", BrotfabrikApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.BROTFABRIK

    private companion object {
        /** Safety bound on the cursor walk; the two categories fill one page today, the unfiltered programme nine. */
        const val MAX_PAGES = 12
    }
}

val BROTFABRIK_LIMITATIONS =
    VenueLimitations(
        EventSource.BROTFABRIK,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the api carries one start time per event and no doors time"),
        AcceptedLimitation(LimitedAspect.END_TIME, "every event ends two hours after it starts, the calendar's default rather than the show's"),
        AcceptedLimitation(LimitedAspect.GENRE, "the house files its nights by room, not by style"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the stage bills productions, and the readings name their authors only in the title and the prose")
    )
