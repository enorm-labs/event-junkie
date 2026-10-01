package de.norm.events.scraper.sisyphos

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure parser for the fan-run Sisyphos timetable at sisy.fan (ADR-036).
 *
 * The home page shows the latest weekend: a heading `HAPPY RAVE HAPPY LIFE (25.09.2026 - 28.09.2026)`,
 * one tab button per floor, and one `div#dancefloor-<n>` table per tab. Each set is a row whose
 * Alpine `x-data` carries its start and end as ISO offsets, with one `/artists/` link per act; a
 * `b2b` slot links both. Break rows carry no `x-data` and are skipped.
 *
 * The weekend becomes one event from its first set to its last. Its `sourceId` is keyed on the
 * Friday, the day the club's calendar opens the weekend, so it stays the same whatever id sisy.fan
 * gives the weekend, and a weekend the calendar did not deliver keeps the calendar's key.
 */
class SisyfanTimetableScraper {
    private val logger = KotlinLogging.logger {}

    /** Every weekend on [document] as an event, with [ScrapedEvent.lineupSourceUrl] set for the credit. */
    fun scrape(document: Document): List<ScrapedEvent> =
        document.select("h2").mapNotNull { heading ->
            val match = HEADING_PATTERN.matchEntire(heading.text().trim()) ?: return@mapNotNull null
            val block = heading.closest("div.mb-8") ?: return@mapNotNull null
            @Suppress("TooGenericExceptionCaught") // One malformed weekend must not abort the import
            try {
                parseWeekend(block, match)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse sisy.fan weekend '${heading.text()}', skipping" }
                null
            }
        }

    private fun parseWeekend(
        block: Element,
        heading: MatchResult
    ): ScrapedEvent? {
        val (title, fromText, toText) = heading.destructured
        val from = LocalDate.parse(fromText, DATE)
        val to = LocalDate.parse(toText, DATE)
        val artists = floors(block).flatMap { (floor, table) -> parseFloor(floor, table) }
        if (artists.isEmpty()) {
            logger.info { "sisy.fan weekend '$title' ($fromText) lists no sets yet, skipping" }
            return null
        }
        val first = artists.mapNotNull { it.setStart }.min().atZone(BERLIN)
        val last = artists.mapNotNull { it.setEnd ?: it.setStart }.max().atZone(BERLIN)
        if (last.toLocalDate().isAfter(to)) logger.warn { "sisy.fan weekend '$title' has a set after its last day $toText" }
        val page = "$BASE_URL/events/from/$fromText/to/$toText"
        return ScrapedEvent(
            title = title.trim(),
            eventType = EventType.PARTY.name,
            eventDate = first.toLocalDate(),
            startTime = first.toLocalTime(),
            endDate = last.toLocalDate(),
            endTime = last.toLocalTime(),
            sourceUrl = page,
            lineupSourceUrl = page,
            sourceId = "${EventSource.SISYPHOS.sourceIdPrefix}$from",
            artists = artists
        )
    }

    /** Each floor's name with its table: tab button `n` names `div#dancefloor-n`. */
    private fun floors(block: Element): List<Pair<String, Element>> =
        block
            .select("button")
            .mapNotNull { button ->
                val id = TAB_PATTERN.find(button.attr("@click"))?.groupValues?.get(1) ?: return@mapNotNull null
                val table = block.getElementById("dancefloor-$id") ?: return@mapNotNull null
                button.text().trim() to table
            }

    private fun parseFloor(
        floor: String,
        table: Element
    ): List<ScrapedArtist> =
        table.select("tr[x-data]").flatMap { row ->
            val slot = row.attr("x-data")
            val start = instantOf(START_PATTERN, slot) ?: return@flatMap emptyList()
            val end = instantOf(END_PATTERN, slot)
            row
                .select("a[href*=/artists/]")
                .map { cleanName(it.text()) }
                .filter { it.isNotEmpty() }
                .map { ScrapedArtist(name = it, stage = floor, setStart = start, setEnd = end) }
        }

    private fun instantOf(
        pattern: Regex,
        slot: String
    ): Instant? {
        val text = pattern.find(slot)?.groupValues?.get(1) ?: return null
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `An On Bast - Live` and `TAMADA (live)` are the act, billed live. */
    private fun cleanName(text: String): String = text.replace(LIVE_SUFFIX, "").trim()

    companion object {
        const val BASE_URL = "https://sisy.fan"

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val HEADING_PATTERN = Regex("""(.+?)\s*\((\d{2}\.\d{2}\.\d{4})\s*-\s*(\d{2}\.\d{2}\.\d{4})\)""")
        private val TAB_PATTERN = Regex("""setActiveTab\((\d+)\)""")
        private val START_PATTERN = Regex("""currentStart:\s*'([^']+)'""")
        private val END_PATTERN = Regex("""currentEnd:\s*'([^']+)'""")
        private val LIVE_SUFFIX = Regex("""\s*(?:-\s*live|\(\s*live\s*\))\s*$""", RegexOption.IGNORE_CASE)
    }
}
