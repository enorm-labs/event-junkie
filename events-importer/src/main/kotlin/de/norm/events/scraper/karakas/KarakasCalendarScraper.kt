package de.norm.events.scraper.karakas

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.PADDED_GERMAN_DATE
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOfStringLiteral
import de.norm.events.scraper.endOn
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * Reads the Karakas calendar out of the script GoDaddy Website Builder publishes for the page. The
 * calendar is entered by hand, and the script passes its props as one escaped JSON string:
 * `componentName:'@widget/CALENDAR/bs-calendar',props:JSON.parse("…")`. Each `manualEvents` entry
 * has an `id`, a `dd.MM.yyyy` date, start and end as `20:30 Uhr`, and a Draft.js description whose
 * "Tickets 👉 <url>" line names the shop. Hand entry has typos: `20:300 Uhr` reads as 20:30.
 */
class KarakasCalendarScraper {
    private val logger = KotlinLogging.logger {}

    /** The calendar's events, or null when [script] does not publish the calendar widget. */
    fun scrape(
        script: String,
        pageUrl: String,
        eventType: EventType
    ): List<ScrapedEvent>? {
        val props = calendarProps(script) ?: return null
        return props
            .path("manualEvents")
            .takeIf { it.isArray }
            ?.toList()
            .orEmpty()
            .mapSkippingFailures(logger, "Karakas calendar event") { toEvent(it, pageUrl, eventType) }
    }

    private fun calendarProps(script: String): JsonNode? {
        val marker = script.indexOf(CALENDAR_WIDGET).takeIf { it >= 0 } ?: return null
        val opening = script.indexOf(PROPS_CALL, marker).takeIf { it >= 0 }?.plus(PROPS_CALL.length) ?: return null
        val closing = endOfStringLiteral(script, opening).takeIf { it >= 0 } ?: return null
        val json = jsonMapper.readValue(script.substring(opening, closing + 1), String::class.java)
        return jsonMapper.readTree(json)
    }

    private fun toEvent(
        node: JsonNode,
        pageUrl: String,
        eventType: EventType
    ): ScrapedEvent? {
        val id = node.stringOrNull("id") ?: return null
        val title = node.stringOrNull("title")?.let(::cleanEventTitle)
        val date = node.stringOrNull("date")?.let { LocalDate.parse(it, PADDED_GERMAN_DATE) }
        if (title.isNullOrBlank() || date == null) {
            logger.warn { "Karakas calendar event '$id' has no title or date, skipping" }
            return null
        }
        val location = node.stringOrNull("location")
        if (location != null && !location.contains(HOUSE_STREET, ignoreCase = true)) {
            logger.debug { "Skipping Karakas calendar event '$title': it runs at $location" }
            return null
        }
        val start = parseClockPrefix(node.stringOrNull("start"))
        val end = parseClockPrefix(node.stringOrNull("end"))
        val lines = draftLines(node.stringOrNull("desc"))
        val ticketLine = lines.firstOrNull { TICKET_LINE.matches(it) }
        return ScrapedEvent(
            title = title,
            description = lines.filterNot { it == ticketLine }.joinToString("\n").ifBlank { null },
            eventType = eventType.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            sourceUrl = pageUrl,
            sourceId = "${EventSource.KARAKAS.sourceIdPrefix}$id",
            ticketUrl = ticketLine?.let { TICKET_LINE.find(it)?.groupValues?.get(1) },
            soldOut = hasSoldOutMarker(title)
        )
    }

    /** The non-blank lines of a Draft.js document, one per block. */
    private fun draftLines(desc: String?): List<String> =
        desc
            ?.let { jsonMapper.readTree(it).path("blocks") }
            ?.takeIf { it.isArray }
            ?.toList()
            .orEmpty()
            .mapNotNull { it.stringOrNull("text")?.trim() }

    private companion object {
        val jsonMapper: JsonMapper = JsonMapper.builder().build()
        const val CALENDAR_WIDGET = "componentName:'@widget/CALENDAR/bs-calendar'"
        const val PROPS_CALL = "props:JSON.parse("
        const val HOUSE_STREET = "Kurfürsten"
        val TICKET_LINE = Regex("""^\s*tickets?\b[^\p{L}\d]*(https?://\S+)\s*$""", RegexOption.IGNORE_CASE)
    }
}
