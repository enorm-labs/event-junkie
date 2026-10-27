package de.norm.events.scraper.wienerblut

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Pure parser for Wiener Blut's Wix Events list (`/event-list`), read from the `wix-warmup-data` payload. The pub lists
 * its gigs there, not its DJ nights. A title is the band, and the one-line description names its style and home town
 * (`Post-Punk aus Hamburg`), so the style before `aus` is the genre.
 */
class WienerBlutOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.WIENER_BLUT) ?: return emptyList()
        logger.info { "Found ${events.size()} event(s) in Wiener Blut Wix warmup payload" }
        return events.mapSkippingFailures(logger, "Wiener Blut event") { parseEvent(it, baseUrl) }
    }

    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val slug = node.stringOrNull("slug") ?: return null.also { logger.warn { "Wiener Blut event has no slug, skipping" } }
        val cleanedTitle = node.stringOrNull("title")?.let(::cleanEventTitle)
        val title = cleanedTitle?.text
        val schedule = parseWixSchedule(node.path("scheduling").path("config"))
        if (title.isNullOrBlank() || schedule.date == null) {
            logger.warn { "Wiener Blut event '$slug' has no title or no start date, skipping" }
            return null
        }
        val description = node.stringOrNull("description")
        val type = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            soldOut = cleanedTitle.soldOut,
            description = description,
            eventType = type,
            typeIsFallback = type == EventType.CONCERT.name,
            eventDate = schedule.date,
            startTime = schedule.startTime,
            endDate = schedule.endDate,
            endTime = schedule.endTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = resolveUrl(baseUrl, "/event-details/$slug"),
            sourceId = "${EventSource.WIENER_BLUT.sourceIdPrefix}$slug",
            genre = description?.let { STYLE_FROM.find(it) }?.groupValues?.get(1),
            status = mapWixEventStatus(node.path("status")),
            artists = buildArtistsForEventType(title, null, type)
        )
    }
}

/** `Post-Punk aus Hamburg`: a style, then `aus` and a capitalised place. */
private val STYLE_FROM = Regex("""^\s*([\p{L}&/ -]+?)\s+aus\s+\p{Lu}""")
