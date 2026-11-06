package de.norm.events.scraper.pandaplatforma

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.TEC_RUNNING_LOOKBACK
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for PANDA platforma, the art space in the Kulturbrauerei, over the venue's
 * **The Events Calendar** REST API.
 *
 * The API filters on the start, so [TEC_RUNNING_LOOKBACK] keeps a running exhibition in the response
 * after its opening day. The walk follows the plugin's `next_rest_url` cursor, and [MAX_PAGES] bounds it.
 * Conditional requests are unused, because the window moves with today and upserts are idempotent by `sourceId`.
 *
 * @see PandaPlatformaApiScraper for the field mapping.
 * @see <a href="https://panda-platforma.berlin/veranstaltungen-in-berlin/">PANDA platforma programme</a>
 */
@Component
class PandaPlatformaWebsiteImporter(
    apiClient: ApiClient,
    clock: Clock = Clock.system(BERLIN)
) : AbstractTecImporter(apiClient, "PANDA platforma", PandaPlatformaApiScraper()::scrapePage, MAX_PAGES, TEC_RUNNING_LOOKBACK, clock) {
    override val eventSource: EventSource = EventSource.PANDA_PLATFORMA

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val PANDA_PLATFORMA_LIMITATIONS =
    VenueLimitations(
        EventSource.PANDA_PLATFORMA,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the api carries one start time per event and no doors time"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "most events set no ticket link of their own"),
        AcceptedLimitation(LimitedAspect.GENRE, "the categories name house series, and only the jazz, experimental and global ones name a style")
    )
