package de.norm.events.scraper.kitkatclub

import de.norm.events.event.EventType
import de.norm.events.scraper.B2B_SEPARATOR
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure parser for KitKatClub's programme page, about one week of nights in a table.
 *
 * - **A row is one night**: `span.ClubTermineDatum` holds `Mi.<br>30. Sep<br>ab 22h`, with no year, and
 * `h4.ClubTermineTitel` the series. Each `p.ClubTermineText` is one labelled field (`Line up:`, `Style:`,
 * `Special:`, `Dresscode:`).
 * - **A line-up split across rooms labels each room**: `Main: A, B. Dragon: C. 4. Raum feat. JUNGLI ARTCAR: D`.
 * Once one label appears, only labelled sentences are read, because the unlabelled ones are prose
 * (`Disco Bizarre (ab ca. 1 Uhr) im 4. Raum, im Basement, mit …`).
 * - **The ticket link is a Resident Advisor URL inside `Special:`**, the only one the club prints.
 * - **The printed time is when the doors open.** A weekend night's `Special:` adds a warm-up before the party,
 * `Von 20 bis 22 Uhr` or `Between 8 and 10 pm`; when it opens at the printed time, its end is the start.
 */
class KitKatClubProgrammePageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("tr:has(> td > span.ClubTermineDatum)")
        logger.info { "Found ${rows.size} night(s) on the KitKatClub programme" }

        return rows.mapSkippingFailures(logger, "KitKatClub night") { row -> parseRow(row, sourceUrl) }
    }

    @Suppress("ReturnCount") // One guard clause per required field reads better than nesting
    private fun parseRow(
        row: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = row.textAt("h4.ClubTermineTitel")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "KitKatClub night on $sourceUrl has no title, skipping" }
            return null
        }
        val dateLines =
            row
                .selectFirst("span.ClubTermineDatum")
                ?.textLines()
                .orEmpty()
                .map { it.replace(NBSP, ' ').trim() }
        val eventDate = parseDate(dateLines)
        if (eventDate == null) {
            logger.warn { "No parseable date for KitKatClub night '$title' in $dateLines, skipping" }
            return null
        }
        val fields = labelledFields(row)
        val special = fields["special"].orEmpty()
        val opens = dateLines.firstNotNullOfOrNull(::parseStartTime)
        val partyStart = opens?.let { warmUpEnd(special, it) }

        return ScrapedEvent(
            title = title,
            description =
                (special.filterNot { BARE_URL.matches(it) } + listOfNotNull(fields["dresscode"]?.joinToString(" ")?.let { "Dresscode: $it" }))
                    .joinToString("\n")
                    .ifEmpty { null },
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            doorsTime = opens.takeIf { partyStart != null },
            startTime = partyStart ?: opens,
            // No page per night, so the date is the identity: the club runs one night per date.
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.KITKATCLUB.sourceIdPrefix}$eventDate",
            ticketUrl = special.firstNotNullOfOrNull { RA_EVENT_URL.find(it)?.value },
            genre = fields["style"]?.joinToString(" ")?.takeIf { it.isNotBlank() },
            artists = fields["line up"]?.joinToString(" ")?.let(::parseLineup).orEmpty()
        )
    }

    /** The `Mi.` / `30. Sep` pair, year from the weekday ([inferYearForWeekday]). */
    @Suppress("ReturnCount") // Guard clauses for each missing date part are clearer than nesting
    private fun parseDate(lines: List<String>): LocalDate? {
        val match = lines.firstNotNullOfOrNull { DAY_MONTH.find(it) } ?: return null
        val month = parseGermanMonthAbbreviation(match.groupValues[2]) ?: return null
        val monthDay = runCatching { MonthDay.of(month, match.groupValues[1].toInt()) }.getOrNull() ?: return null
        val weekday = lines.firstNotNullOfOrNull { parseGermanWeekdayAbbreviation(it.trim('.', ' ')) }
        return inferYearForWeekday(monthDay, weekday, clock)
    }

    private fun parseStartTime(line: String): LocalTime? =
        START.find(line)?.let { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].ifEmpty { "0" }.toInt()) }.getOrNull() }

    /** The end of a warm-up window that opens at [opens], read from the night's notes; `null` without one. */
    private fun warmUpEnd(
        special: List<String>,
        opens: LocalTime
    ): LocalTime? =
        special
            .flatMap { WARM_UP.findAll(it) }
            .map { match -> match.destructured.let { (from, to, unit) -> clockHour(from, unit) to clockHour(to, unit) } }
            .firstOrNull { (from, _) -> from == opens.hour }
            ?.let { (_, to) -> LocalTime.of(to % HOURS_PER_DAY, 0) }

    /** An hour as printed, moved into the evening when the text says `pm`. */
    private fun clockHour(
        hour: String,
        unit: String
    ): Int = hour.toInt() + if (unit.equals("pm", ignoreCase = true) && hour.toInt() < NOON) NOON else 0

    /** Each `p.ClubTermineText` keyed by its lower-cased label without the colon, its `<br>` lines as the value. */
    private fun labelledFields(row: Element): Map<String, List<String>> =
        row.select("p.ClubTermineText").associate { paragraph ->
            val label = paragraph.textAt("span.ClubTerminePunkt").orEmpty()
            val lines = paragraph.textLines().map { it.replace(NBSP, ' ').trim() }.filter { it.isNotBlank() }
            val value =
                listOfNotNull(
                    lines
                        .firstOrNull()
                        ?.removePrefix(label)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                ) + lines.drop(1)
            label.trimEnd(':', ' ').lowercase() to value
        }

    /**
     * The acts, each on the room its label names. A sentence `<room>: <acts>` opens a room; a label too
     * long to be a room (`Ebenfalls bereits ab 20 Uhr separate Veranstaltung im Basement`) drops its sentence.
     */
    private fun parseLineup(text: String): List<ScrapedArtist> {
        val sentences = text.split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotBlank() }
        val labelled = sentences.mapNotNull { sentence -> ROOM_LABEL.find(sentence)?.let { it.groupValues[1] to it.groupValues[2] } }
        val segments =
            if (labelled.isEmpty()) {
                listOf(null to text)
            } else {
                labelled.mapNotNull { (label, acts) -> roomOf(label)?.let { room -> room.ifEmpty { null } to acts } }
            }
        return segments
            .flatMap { (room, acts) -> splitActs(acts).map { ScrapedArtist(name = it, role = "DJ", stage = room) } }
            // One act on two floors would be two rows for one (event, artist) pair; the first billing wins.
            .distinctBy { it.name.lowercase() }
    }

    /**
     * The room a label names, `""` for a label that is only a time or a generic `Weitere Decks`, and `null`
     * for a label that is prose. A curator after `feat.` and a time slot come off first.
     */
    private fun roomOf(label: String): String? {
        val room =
            label
                .substringBefore(" feat.")
                .replace(TIME_PHRASE, " ")
                .replace(WHITESPACE, " ")
                .trim()
        return when {
            room.split(' ').size > MAX_ROOM_WORDS -> null
            room.isEmpty() || GENERIC_ROOM.matches(room) -> ""
            else -> room
        }
    }

    private fun splitActs(acts: String): List<String> =
        acts
            .split(',')
            .flatMap { it.split(B2B_SEPARATOR) }
            .flatMap(::splitSegmentOnConjunctions)
            .map {
                it
                    .trim()
                    .trimEnd('.')
                    .trim('*')
                    .trim()
            }
            // Prose is judged before the suffix strip, which would cut a quoted title off a sentence.
            .filter { it.split(WHITESPACE).size <= MAX_ACT_WORDS && !PROSE_MARK.containsMatchIn(it) }
            .map(::stripArtistSuffix)
            .filter { it.isNotBlank() && !isNonArtistName(it) }

    private companion object {
        const val NBSP = '\u00A0'

        val DAY_MONTH = Regex("""(\d{1,2})\.\s*(\p{L}{3,5})\.?""")

        /** `ab 22h`, `ab 08h`, `ab 22:30h`. */
        val START = Regex("""\bab\s+(\d{1,2})(?:[:.](\d{2}))?\s*h\b""", RegexOption.IGNORE_CASE)

        /** The weekend warm-up: `Von 20 bis 22 Uhr` or `Between 8 and 10 pm`. */
        val WARM_UP = Regex("""\b(?:von|between)\s+(\d{1,2})\s+(?:bis|and)\s+(\d{1,2})\s*(uhr|pm)\b""", RegexOption.IGNORE_CASE)

        const val NOON = 12

        const val HOURS_PER_DAY = 24

        val RA_EVENT_URL = Regex("""https://(?:[a-z]{2}\.)?ra\.co/events/\d+""")

        val BARE_URL = Regex("""https?://\S+""")

        /**
         * A full stop that ends a sentence of the line-up, not one inside `4. Raum`, `D.J.`, `Mr. Confuse`,
         * `feat.` or `ca. 1 Uhr`: it needs two letters, a bracket or a closing quote before it.
         */
        val SENTENCE_BREAK = Regex("""(?<![Ff]eat|ca|Mr|Dr|St)(?<=\p{L}{2}|[)“"])\.\s+""")

        /** `<room>: <acts>`, the room before the first colon. */
        val ROOM_LABEL = Regex("""^([^:]{1,60}):\s+(.+)$""")

        /** `20-22 Uhr`, `ab 22 Uhr`, `(ab ca. 1 Uhr)`. */
        val TIME_PHRASE = Regex("""\(?\b(?:ab\s+(?:ca\.\s*)?)?\d{1,2}(?:[:.]\d{2})?(?:\s*-\s*\d{1,2}(?:[:.]\d{2})?)?\s*Uhr\)?""", RegexOption.IGNORE_CASE)

        val GENERIC_ROOM = Regex("""(?:weitere\s+)?decks?|line\s*up""", RegexOption.IGNORE_CASE)

        const val MAX_ROOM_WORDS = 3

        const val MAX_ACT_WORDS = 5

        /** A quotation or a clock time marks prose (`Classical music at the pool „The Naked String Quartet“`). */
        val PROSE_MARK = Regex("""[„“"]|\bUhr\b|\d{1,2}:\d{2}""")
    }
}
