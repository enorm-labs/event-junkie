package de.norm.events.scraper.loge

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.buildArtistList
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Pure parser for Loge's Wix Events listing (`/event-list`) page.
 *
 * Reads every event from the embedded `wix-warmup-data` JSON (see
 * [WixEventsWarmupData]). This overview payload serves two purposes:
 * 1. **Discovery** — each entry's `slug` yields the `/event-details/<slug>`
 *    detail URL (and the stable `sourceId`) that
 *    [LogeWebsiteImporter] fetches for the ticket price.
 * 2. **Authoritative source for the core fields** — title, Berlin-local date and
 *    start time, poster image, and the artist roster (parsed from the `+`-joined
 *    title). The detail page (schema.org JSON-LD) only adds the price and
 *    confirms the status, so everything here except the price survives even when
 *    a detail page cannot be fetched.
 *
 * @see LogeDetailPageScraper for the per-event price/status enrichment.
 * @see LogeWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.loge-berlin.org/event-list">Loge event listing</a>
 */
class LogeOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the overview page's embedded Wix warmup payload.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve
     *   per-event `/event-details/<slug>` detail URLs.
     * @return a list of [ScrapedEvent] instances, one per listed event.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.LOGE)
        if (events == null) {
            return emptyList()
        }
        logger.info { "Found ${events.size()} event(s) in Loge Wix warmup payload" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import
        return events.mapNotNull { node ->
            try {
                parseEvent(node, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Loge overview event, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required slug and title are clearer than nesting
    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val slug = node.stringOrNull("slug")
        if (slug == null) {
            logger.warn { "Loge overview event has no slug, skipping" }
            return null
        }
        val title = node.stringOrNull("title")
        if (title == null) {
            logger.warn { "Loge overview event '$slug' has no title, skipping" }
            return null
        }

        val (eventDate, startTime) = parseWixSchedule(node.path("scheduling").path("config"))
        return ScrapedEvent(
            title = title,
            // Loge is a live-music venue with no category field; default to CONCERT so events aren't
            // filed as OTHER. A festival title (e.g. "… Soli-Festival") is still promoted to FESTIVAL
            // at the persistence boundary (see ScrapedEvent.resolveEventType).
            eventType = EventType.CONCERT.name,
            // Fall back to the sentinel for a rare to-be-decided date; the detail page (schema.org
            // startDate) then supplies it via LogeWebsiteImporter.fillGapsFromOverview.
            eventDate = eventDate ?: UNRESOLVED_EVENT_DATE,
            startTime = startTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = resolveUrl(baseUrl, "/event-details/$slug"),
            sourceId = "${EventSource.LOGE.sourceIdPrefix}$slug",
            artists = parseArtists(title)
        )
    }

    /**
     * Builds the artist roster from the `+`-joined title, Loge's support-act
     * convention (e.g. `"ESTAMOE + DALOY! + FURIE"` → headliner ESTAMOE, support
     * DALOY! and FURIE). The first `+`-segment is the headliner(s), the rest are
     * support acts; [buildArtistList] drops placeholder/role names (a trailing
     * `"+ Support"` collapses to no support act). Titles without a `+` yield no
     * artists — a single segment can be a band or an event name ("MENTAL RIOT
     * (Soli-Festival)"), so extracting one would be unreliable.
     */
    private fun parseArtists(title: String): List<ScrapedArtist> {
        val segments = title.split(TITLE_SUPPORT_SEPARATOR)
        if (segments.size < 2) return emptyList()
        return buildArtistList(segments.first(), segments.drop(1))
    }
}

/** Splits a Loge title on the `+` support-act separator, tolerating surrounding whitespace. */
private val TITLE_SUPPORT_SEPARATOR = Regex("""\s*\+\s*""")
