package de.norm.events.scraper.speiches

import de.norm.events.event.EventType
import de.norm.events.genretag.isGenreLabel
import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Pure HTML parser for the Speiche programme table that rockradio.de hosts for the pub.
 *
 * Each row has five cells: `Fr 09.10.`, `20.00`, the kind (`Konzert` or `rockradio.de-live`) over
 * `Ort: …`, a poster, and one line of text. Every cell links to `veranstaltungen_info.php?auswahl_lfdnr=<n>`,
 * whose number identifies the event. The traps:
 *
 * - **Rows at `Ort: Club23`** are the Kulturbrauerei club, another venue, and are skipped.
 * - **No year.** The row's year is the one within a year of today whose date falls on the printed weekday.
 * Placeholder rows dated `01.01.` (stored as 2039 on the event page) fit no such year and are skipped.
 * - **The text** is `<act> - <style> - Eintritt immer frei`: the first part is the title, the rest the
 * subtitle, and the entry note sets [ScrapedEvent.free]. The house series (`Jazz am Sonntag`,
 * `Speiches Open Stage`) name no act in the title; `Jazz am Sonntag` names its band before `spielt`.
 * - **`rockradio.de-live`** is the radio's own broadcast from the pub, a talk as often as a band, so it is [EventType.OTHER].
 *
 * @see SpeichesWebsiteImporter for the HTTP fetch orchestrator.
 */
class SpeichesOverviewPageScraper(
    /** Clock for the year inference; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("tr:has(> td a[href*=auswahl_lfdnr])")
        logger.info { "Found ${rows.size} programme row(s) on the Speiche page" }
        return rows.mapSkippingFailures(logger, "Speiche programme row") { parseRow(it, baseUrl) }
    }

    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val cells = row.select("> td")
        val (id, segments, date) = listingAtPub(row, cells) ?: return null
        val title = segments.first()
        val subtitle = segments.drop(1).joinToString(" – ").ifBlank { null }
        val eventType = if (cells[KIND_CELL].selectFirst("a")?.text() == CONCERT_KIND) EventType.CONCERT else EventType.OTHER

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType.name,
            eventDate = date,
            startTime = parseTime(cells.getOrNull(TIME_CELL)?.text(), DOTTED_TIME),
            imageUrl =
                row
                    .selectFirst("img[src]")
                    ?.attr("src")
                    ?.takeUnless { it.contains("logo") }
                    ?.let { resolveUrl(baseUrl, it) },
            sourceUrl = resolveUrl(baseUrl, "veranstaltungen_info.php?auswahl_lfdnr=$id"),
            sourceId = "${EventSource.SPEICHES.sourceIdPrefix}$id",
            genre = normalizeGenre(subtitle).filter(::isGenreLabel).joinToString(", ").ifBlank { null },
            free = FREE_ENTRY.containsMatchIn(cells[TEXT_CELL].text()),
            artists = if (eventType == EventType.CONCERT) actsOf(title, subtitle) else emptyList()
        )
    }

    /** The row's event number, text segments and date, or `null` for a row at another place, without text, or without a date that fits. */
    private fun listingAtPub(
        row: Element,
        cells: List<Element>
    ): Triple<String, List<String>, LocalDate>? {
        val id = row.selectFirst("a[href*=auswahl_lfdnr]")?.attr("href")?.let { EVENT_ID.find(it)?.groupValues?.get(1) }
        val place = cells.getOrNull(KIND_CELL)?.text()?.let { PLACE.find(it)?.groupValues?.get(1) }
        val segments =
            cells
                .getOrNull(TEXT_CELL)
                ?.takeIf { place == HOUSE_PLACE }
                ?.let(::textWithBreaksAsDashes)
                ?.split(SEGMENT_DASH)
                ?.map(::withoutNotes)
                ?.filter { it.isNotBlank() }
                ?.ifEmpty { null }
        val date = cells.getOrNull(DATE_CELL)?.text()?.let(::dateOf)
        if (id == null || segments == null || date == null) {
            logger.debug { "Skipping Speiche row ${id ?: "?"} at '$place' dated '${cells.firstOrNull()?.text()}'" }
            return null
        }
        return Triple(id, segments, date)
    }

    /** [segment] without the entry note, the series reminder and a trailing ellipsis. */
    private fun withoutNotes(segment: String): String =
        listOf(FREE_ENTRY, REPEAT_NOTE, TRAILING_ELLIPSIS).fold(segment) { text, note -> note.replace(text, " ") }.trim()

    /** The text cell with each `<br>` read as a segment dash, as the venue uses it, and its `´` apostrophes folded. */
    private fun textWithBreaksAsDashes(cell: Element): String? {
        val copy = cell.clone()
        copy.select("br").forEach { it.replaceWith(TextNode(" - ")) }
        return copy
            .text()
            .replace('´', '\'')
            .trim()
            .ifBlank { null }
    }

    /** The date in the year within a year of today on which `DD.MM.` falls on the printed weekday, or `null`. */
    private fun dateOf(cell: String): LocalDate? {
        val match = DATE.find(cell.replace('\u00a0', ' '))
        val weekday = match?.let { parseGermanWeekdayAbbreviation(it.groupValues[1]) } ?: return null
        val (day, month) = match.destructured.let { it.component2().toInt() to it.component3().toInt() }
        val today = LocalDate.now(clock)
        return (today.year - 1..today.year + 1)
            .mapNotNull { year -> runCatching { LocalDate.of(year, month, day) }.getOrNull() }
            .filter { it.dayOfWeek == weekday && it >= today.minusMonths(2) && it <= today.plusYears(1) }
            .minByOrNull { abs(it.toEpochDay() - today.toEpochDay()) }
    }

    /**
     * The acts of a concert: the band before `spielt` in a house series, otherwise the title's names
     * without a country tag, an instrument note (`Marcos Coll m-harp`), a band's line-up after `=`, or a series label before `:`.
     */
    private fun actsOf(
        title: String,
        subtitle: String?
    ): List<ScrapedArtist> {
        if (HOUSE_SERIES.containsMatchIn(title)) {
            return subtitle?.let { PLAYS.find(it)?.groupValues?.get(1) }?.let { listOf(ScrapedArtist(name = it, role = "HEADLINER")) }.orEmpty()
        }
        val names =
            title
                .substringBefore(" = ")
                .replace(COUNTRY_TAG, "")
                .replace(SERIES_LABEL, "")
        return names
            .split(", ")
            .map { INSTRUMENT_NOTE.replace(it, "").trim() }
            .flatMap { headlinersFromTitle(it) }
            .distinctBy { it.name }
    }

    private companion object {
        const val DATE_CELL = 0
        const val TIME_CELL = 1
        const val KIND_CELL = 2
        const val TEXT_CELL = 4
        const val HOUSE_PLACE = "Speiche"
        const val CONCERT_KIND = "Konzert"

        /** The table prints `20.00`. */
        val DOTTED_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH.mm")

        val EVENT_ID = Regex("""auswahl_lfdnr=(\d+)""")
        val PLACE = Regex("""Ort:\s*(\S+)""")
        val DATE = Regex("""([A-Za-z]{2})\s+(\d{1,2})\.(\d{1,2})\.""")

        /** The dash between the parts of the text; `Zigarrenkisten- Gitarren` has no space before its hyphen. */
        val SEGMENT_DASH = Regex("""\s+[-–]\s+""")

        /** `Eintritt immer frei`, `immer Eintritt frei`, and the `Einritt Frei` typo. */
        val FREE_ENTRY = Regex("""(?i)(?:immer\s+)?ein(?:t)?ritt\s+(?:immer\s+)?frei""")

        /** The reminder a series row ends on: `... jeden Sonntag`, `jetzt jeden Montag`. */
        val REPEAT_NOTE = Regex("""(?i)(?:jetzt\s+)?jeden\s+(?:Montag|Sonntag)\s*$""")

        val TRAILING_ELLIPSIS = Regex("""\s*\.{2,}\s*$""")

        val HOUSE_SERIES = Regex("""(?i)^(?:jazz am sonntag|speiches open stage|beste handgemachte musik)""")
        val PLAYS = Regex("""^(.+?)\s+spielt\b""")
        val COUNTRY_TAG = Regex("""\s*\([A-Z]{1,3}(?:/[A-Z]{1,3})*\)""")
        val SERIES_LABEL = Regex("""^[^:]+:\s+""")

        /** Lower-case words after a name, the instrument abbreviations of `Remy Bankyln g. voc`. */
        val INSTRUMENT_NOTE = Regex("""(?:\s+[a-z][\w.-]*)+$""")
    }
}
