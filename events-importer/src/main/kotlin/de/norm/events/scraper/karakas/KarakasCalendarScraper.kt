package de.norm.events.scraper.karakas

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.PADDED_GERMAN_DATE
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOfStringLiteral
import de.norm.events.scraper.endOn
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.time.LocalDate

/**
 * Reads the Karakas calendar out of the script GoDaddy Website Builder publishes for the page. The
 * calendar is entered by hand, and the script passes its props as one escaped JSON string:
 * `componentName:'@widget/CALENDAR/bs-calendar',props:JSON.parse("…")`. Each `manualEvents` entry
 * has an `id`, a `dd.MM.yyyy` date, start and end as `20:30 Uhr`, and a Draft.js description whose
 * "Tickets 👉 <url>" line names the shop. The shop link can instead sit on the word "Tickets" as a
 * LINK entity, with no URL in the text. Hand entry has typos: `20:300 Uhr` reads as 20:30.
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
        val json = SCRAPER_JSON.readValue(script.substring(opening, closing + 1), String::class.java)
        return SCRAPER_JSON.readTree(json)
    }

    private fun toEvent(
        node: JsonNode,
        pageUrl: String,
        eventType: EventType
    ): ScrapedEvent? {
        val id = node.stringOrNull("id") ?: return null
        val cleanedTitle = node.stringOrNull("title")?.let(::cleanEventTitle)
        val title = cleanedTitle?.text
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
        val ticketLine = lines.firstOrNull { it.ticketUrl() != null }
        return ScrapedEvent(
            title = title,
            description = lines.filterNot { it == ticketLine }.joinToString("\n") { it.text }.ifBlank { null },
            eventType = eventType.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            sourceUrl = pageUrl,
            sourceId = "${EventSource.KARAKAS.sourceIdPrefix}$id",
            ticketUrl = ticketLine?.ticketUrl(),
            soldOut = cleanedTitle.soldOut
        )
    }

    /** The trimmed non-blank lines of a Draft.js document, each with the links whose range overlaps it. */
    private fun draftLines(desc: String?): List<DraftLine> {
        val doc = desc?.let { SCRAPER_JSON.readTree(it) } ?: return emptyList()
        val entityMap = doc.path("entityMap")
        return doc
            .path("blocks")
            .takeIf { it.isArray }
            ?.flatMap { blockLines(it, entityMap) }
            .orEmpty()
    }

    // The block text is read untrimmed, because entity offsets count from its first character.
    private fun blockLines(
        block: JsonNode,
        entityMap: JsonNode
    ): List<DraftLine> {
        val text = block.path("text").asString("")
        val links = block.path("entityRanges").mapNotNull { linkRange(it, entityMap) }
        return LINE
            .findAll(text)
            .mapNotNull { match ->
                val line = match.value.trim()
                if (line.isEmpty()) return@mapNotNull null
                val start = match.range.first + match.value.indexOf(line)
                val lineRange = start until start + line.length
                DraftLine(
                    text = line,
                    links = links.filter { (range) -> range.overlaps(lineRange) }.map { (range, url) -> range.shift(-start) to url }
                )
            }.toList()
    }

    private fun linkRange(
        range: JsonNode,
        entityMap: JsonNode
    ): Pair<IntRange, String>? {
        val entity = entityMap.path(range.path("key").asString(""))
        if (entity.stringOrNull("type") != "LINK") return null
        val url = entity.path("data").stringOrNull("url")?.let(::lastUrl) ?: return null
        val offset = range.path("offset").asInt(-1).takeIf { it >= 0 } ?: return null
        val length = range.path("length").asInt(0).takeIf { it > 0 } ?: return null
        return offset until offset + length to url
    }

    /** A line of a Draft.js block, with each LINK entity's range relative to the line. */
    private data class DraftLine(
        val text: String,
        val links: List<Pair<IntRange, String>>
    ) {
        fun ticketUrl(): String? {
            TICKET_LINE.find(text)?.let { return it.groupValues[1] }
            val word =
                TICKET_WORD
                    .find(text)
                    ?.groups
                    ?.get(1)
                    ?.range ?: return null
            return links.firstOrNull { (range) -> range.overlaps(word) }?.second
        }
    }

    private companion object {
        const val CALENDAR_WIDGET = "componentName:'@widget/CALENDAR/bs-calendar'"
        const val PROPS_CALL = "props:JSON.parse("
        const val HOUSE_STREET = "Kurfürsten"
        val TICKET_LINE = Regex("""^\s*tickets?\b[^\p{L}\d]*(https?://\S+)\s*$""", RegexOption.IGNORE_CASE)
        val TICKET_WORD = Regex("""^\s*(tickets?)\b""", RegexOption.IGNORE_CASE)
        val LINE = Regex("""[^\n]+""")
        val WHITESPACE = Regex("""\s+""")

        // One entry reads `http:// https://…`; a whole-word split leaves a URL in a query parameter intact.
        fun lastUrl(url: String): String = url.trim().split(WHITESPACE).last()

        fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

        fun IntRange.shift(by: Int): IntRange = first + by..last + by
    }
}
