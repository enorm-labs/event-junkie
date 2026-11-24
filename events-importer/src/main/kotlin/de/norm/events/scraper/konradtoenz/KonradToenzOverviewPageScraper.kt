package de.norm.events.scraper.konradtoenz

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.genretag.isGenreLabel
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.knownGenresInStyleTail
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.parseListing
import de.norm.events.scraper.splitDjList
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for the events block on Konrad Tönz's one-page site builder. Each night is one
 * `section.events-dates` holding text paragraphs: a `Freitag, den 16.10.2026 ab 21 Uhr` line, the
 * night's name (`Good Vibes mit DJ Turbo Pascal`), then usually a style line (`Psych, Disco, Kraut & Beat`).
 *
 * The page is typed by hand. A date line the pattern cannot read (`23.10.20256`) is skipped with a
 * `WARN`, never repaired; a weekday that contradicts the date keeps the printed date. DJs come from the
 * `mit DJ …`, `mit DJane …` or `to DJ …` tail, or from a `DJ …` that ends the name (`… early 70s DJ Nolovigo`, event 15042). A
 * night whose name bills no DJ takes them from its style line, up to the first genre word (event 15050). A `<br>`
 * inside a paragraph breaks the line, so a style line typed below the name is still the style line. A night typed as one
 * line runs the styles into the name (`mit DJ Herr Siebendaum Funk & Disco Spezial`), so there the name stops at the first
 * genre word.
 *
 * A date the bar is shut or booked is no night: `Geschlossen!!!!`, `Geschlossene Veranstaltung!!!!` and an `Offener Abend!!`
 * without a programme are dropped (events 15043, 15045–15047, 15049; EVENT_SCOPE.md).
 */
class KonradToenzOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val nights = document.select("section.events-dates")
        return nights.parseListing(logger, "a Konrad Tönz night") { parseNight(it, sourceUrl) }
    }

    private fun parseNight(
        night: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val lines = night.select("p").flatMap { it.textLines() }.mapNotNull { it.clean().blankToNull() }
        val dateIndex = lines.indexOfFirst { DATE_LINE_START.containsMatchIn(it) }
        if (dateIndex < 0) {
            logger.warn { "Konrad Tönz night without a date line, skipping: $lines" }
            return null
        }
        val dateLine = lines[dateIndex]
        val date = parseDate(dateLine)
        if (date == null) {
            logger.warn { "Konrad Tönz date line '$dateLine' is unreadable, skipping the night" }
            return null
        }
        val (title, style) = lines.drop(dateIndex + 1).let { it.firstOrNull() to it.drop(1).joinToString(" ").blankToNull() }
        if (title == null) {
            logger.warn { "Konrad Tönz night '$dateLine' has no name, skipping" }
            return null
        }
        if (NOT_A_NIGHT.matches(title)) {
            logger.info { "Konrad Tönz date $date is '$title', no night, skipping" }
            return null
        }
        return ScrapedEvent(
            title = title,
            subtitle = style,
            eventType = EventType.PARTY.name,
            eventDate = date,
            startTime = START.find(dateLine)?.let { parseStart(it.groupValues[1], it.groupValues[2]) },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.KONRAD_TOENZ.sourceIdPrefix}$date",
            genre = knownGenresInStyleTail(style),
            artists = djsIn(title, stylesInTitle = style == null).ifEmpty { djsIn(style.orEmpty(), stylesInTitle = true) }
        )
    }

    private fun parseDate(line: String): LocalDate? {
        val parts = DATE.find(line)?.groupValues ?: return null
        val date =
            try {
                LocalDate.of(parts[YEAR].toInt(), parts[MONTH].toInt(), parts[DAY].toInt())
            } catch (_: DateTimeException) {
                return null
            }
        // The page names no neighbouring nights to judge a corrected month by, so the printed date stands.
        return dateCheckedAgainstWeekday(date, parseGermanWeekday(parts[WEEKDAY]), line, plausible = { false })
    }

    private fun parseStart(
        hour: String,
        minute: String
    ): LocalTime? =
        try {
            LocalTime.of(hour.toInt(), minute.ifEmpty { "0" }.toInt())
        } catch (_: DateTimeException) {
            null
        }

    private fun djsIn(
        title: String,
        stylesInTitle: Boolean
    ): List<ScrapedArtist> {
        val tail = (DJ_TAIL.find(title) ?: DJ_ENDS_NAME.find(title))?.groupValues?.get(1) ?: return emptyList()
        val names =
            splitDjList(if (stylesInTitle) beforeFirstGenreWord(tail) else tail)
                .mapNotNull { it.trim().replace(TRAILING_BANGS, "").blankToNull() }
        return (if (DJANE_OPENER.containsMatchIn(tail)) djaneListNames(names) else names)
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = ArtistRole.DJ) }
    }

    /**
     * `DJane Fräulein Philine & Turbo Pascal` names two DJs under one opener. The opener comes off the first name, and a later name
     * without its own `DJ` takes it, as the page bills its DJs elsewhere: `Fräulein Philine`, `DJ Turbo Pascal` (event 15050).
     */
    private fun djaneListNames(names: List<String>): List<String> =
        names.mapIndexed { index, name ->
            when {
                index == 0 -> name.replace(DJANE_OPENER, "")
                DJ_WORD.containsMatchIn(name) -> name
                else -> "DJ $name"
            }
        }

    /** `DJ Herr Siebendaum Funk & Disco Spezial` → `DJ Herr Siebendaum`; empty when no name precedes the styles. */
    private fun beforeFirstGenreWord(tail: String): String {
        val words = tail.split(' ')
        val name = words.drop(1).takeWhile { !isGenreLabel(it.trimEnd(',', ';')) }
        return if (name.isEmpty()) "" else (listOf(words.first()) + name).joinToString(" ")
    }

    private fun String.clean(): String = replace(SPACES, " ").trim()

    private companion object {
        val SPACES = Regex("""[\s ]+""")

        /** A line that starts like a date line, readable or not: a weekday, then a day and a dot. */
        val DATE_LINE_START = Regex("""^\p{L}+,?\s+(?:den\s+)?\d{1,2}\.""")

        /** `Freitag, den 16.10.2026`; a year of more than four digits is a typo and does not match. */
        val DATE = Regex("""^(\p{L}+),?\s+(?:den\s+)?(\d{1,2})\.(\d{1,2})\.(\d{4})(?!\d)""")
        const val WEEKDAY = 1
        const val DAY = 2
        const val MONTH = 3
        const val YEAR = 4

        /** `ab 21 Uhr`, `ab 21:30 Uhr`. */
        val START = Regex("""\bab\s+(\d{1,2})(?:[:.](\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)

        /**
         * The acts after `mit DJ`, `mit DJane` or `to DJ`: `Rillenrodeo @ Tönz mit DJ gina d`orio & DJ johnboyadonis`,
         * `Say yes! to DJ Nonoyesnonono!`, `mit DJane Fräulein Philine & Turbo Pascal`.
         */
        val DJ_TAIL = Regex("""\b(?:mit|to)\s+(DJ(?:ane)?s?\s+.+)$""", RegexOption.IGNORE_CASE)

        /** The `DJane` or `DJanes` that opens a list of DJs. */
        val DJANE_OPENER = Regex("""^DJanes?\s+""", RegexOption.IGNORE_CASE)

        /** A name that carries its own `DJ` word. */
        val DJ_WORD = Regex("""^DJ(?:ane)?s?\s""", RegexOption.IGNORE_CASE)

        /** A name that ends on its DJ with no `mit` before it: `Soulblock- rare soul Music from the 60s to early 70s DJ Nolovigo`. */
        val DJ_ENDS_NAME = Regex("""\s(DJs?\s+\S.*)$""")

        /** The bar shut, booked for a private party, or open with no programme. */
        val NOT_A_NIGHT = Regex("""(?:geschlossen|geschlossene\s+veranstaltung|offener\s+abend)\s*!*""", RegexOption.IGNORE_CASE)

        /** The exclamation a title ends on, `DJ Nonoyesnonono!`, which is not part of the name. */
        val TRAILING_BANGS = Regex("""!+$""")
    }
}
