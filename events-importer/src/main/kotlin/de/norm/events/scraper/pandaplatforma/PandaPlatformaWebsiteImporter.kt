package de.norm.events.scraper.pandaplatforma

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.walkTecEvents
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for PANDA platforma, the art space in the Kulturbrauerei, over the venue's
 * **The Events Calendar** REST API.
 *
 * The API returns the whole upcoming programme; the listing page renders the same events. The
 * walk follows the plugin's `next_rest_url` cursor, and [MAX_PAGES] bounds it. Conditional
 * requests are unused, because the window moves with today and upserts are idempotent by `sourceId`.
 *
 * @see PandaPlatformaApiScraper for the field mapping.
 * @see <a href="https://panda-platforma.berlin/veranstaltungen-in-berlin/">PANDA platforma programme</a>
 */
@Component
class PandaPlatformaWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.PANDA_PLATFORMA

    private val apiScraper = PandaPlatformaApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = apiClient.walkTecEvents(eventSource, url, MAX_PAGES, apiScraper::scrapePage)
        logger.info { "Scraped ${listing.items.size} event(s) from PANDA platforma across ${listing.pages} page(s)" }

        return ImportResult.Success(events = listing.items, etag = null, lastModified = null, complete = listing.complete)
    }

    companion object {
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
