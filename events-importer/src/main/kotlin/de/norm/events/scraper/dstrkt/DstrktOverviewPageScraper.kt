package de.norm.events.scraper.dstrkt

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parser for the home page's "UPCOMING EVENTS" repeater, read from the Wix Data collection
 * `UPCOMINGEVENTS` in the `wix-warmup-data` script, not from the rendered cards. It is a CMS
 * collection, not a Wix Events widget, so [de.norm.events.scraper.WixEventsWarmupData] does not
 * apply. A record holds a title, a UTC `date`, an optional last day `date2nd`, an RA ticket link
 * and an optional rich-text `eventbeschreibung`. The record's `_id` is the night's identity.
 */
class DstrktOverviewPageScraper {
    private val logger = KotlinLogging.logger {}
    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val records = records(document) ?: return emptyList()
        logger.info { "Found ${records.size()} record(s) in the DSTRKT UPCOMINGEVENTS collection" }
        return records.mapSkippingFailures(logger, "DSTRKT event record") { parseRecord(it, baseUrl) }
    }

    private fun records(document: Document): JsonNode? {
        val script = document.getElementById("wix-warmup-data")
        if (script == null) {
            logger.warn { "No 'wix-warmup-data' script on the DSTRKT home page" }
            return null
        }
        val records =
            runCatching { jsonMapper.readTree(script.data()) }
                .onFailure { logger.warn(it) { "Failed to parse the DSTRKT wix-warmup-data JSON" } }
                .getOrNull()
                ?.path("appsWarmupData")
                ?.path("dataBinding")
                ?.path("dataStore")
                ?.path("recordsByCollectionId")
                ?.path(COLLECTION)
                ?.takeIf { it.isObject }
        if (records == null) logger.warn { "No $COLLECTION collection in the DSTRKT warmup payload" }
        return records
    }

    private fun parseRecord(
        record: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val id = record.stringOrNull("_id") ?: return null.also { logger.warn { "DSTRKT record has no _id, skipping" } }
        val title = record.stringOrNull("title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "DSTRKT record '$id' has no title, skipping" }
            return null
        }
        val start = record.path("date").stringOrNull("\$date")?.let { parseInstant(it) }
        if (start == null) {
            logger.warn { "DSTRKT record '$id' has no parseable date, skipping" }
            return null
        }
        val endDate = record.stringOrNull("date2nd")?.let { parseIsoDate(it) }?.takeIf { it > start.toLocalDate() }
        return ScrapedEvent(
            title = title,
            description = htmlParagraphText(record.path("eventbeschreibung").asString("")),
            // The repeater lists the club nights; the hire business behind them never appears here.
            eventType = EventType.PARTY.name,
            typeIsFallback = true,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = endDate,
            sourceUrl = baseUrl,
            sourceId = "${EventSource.DSTRKT.sourceIdPrefix}$id",
            ticketUrl = record.stringOrNull("tickets")
        )
    }

    private fun parseInstant(text: String) =
        try {
            Instant.parse(text).atZone(BERLIN)
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        const val COLLECTION = "UPCOMINGEVENTS"
    }
}
