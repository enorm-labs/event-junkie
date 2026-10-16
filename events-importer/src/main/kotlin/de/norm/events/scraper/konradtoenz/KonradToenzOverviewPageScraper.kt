package de.norm.events.scraper.konradtoenz

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.knownGenresInStyleTail
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekday
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
 * `mit DJ …` tail, and only when a separate style line follows: a night typed as one paragraph runs the
 * styles into the name (`mit DJ Herr Siebendaum Funk & Disco Spezial`), so it bills no one.
 */
class KonradToenzOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val nights = document.select("section.events-dates")
        logger.info { "Found ${nights.size} night(s) on the Konrad Tönz page" }
        return nights.mapSkippingFailures(logger, "a Konrad Tönz night") { parseNight(it, sourceUrl) }
    }

    private fun parseNight(
        night: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val lines = night.select("p").mapNotNull { it.text().clean().blankToNull() }
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
        return ScrapedEvent(
            title = title,
            subtitle = style,
            eventType = EventType.PARTY.name,
            eventDate = date,
            startTime = START.find(dateLine)?.let { parseStart(it.groupValues[1], it.groupValues[2]) },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.KONRAD_TOENZ.sourceIdPrefix}$date",
            genre = knownGenresInStyleTail(style),
            artists = if (style == null) emptyList() else djsIn(title)
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

    private fun djsIn(title: String): List<ScrapedArtist> =
        DJ_TAIL
            .find(title)
            ?.groupValues
            ?.get(1)
            .orEmpty()
            .split(DJ_SEPARATOR)
            .mapNotNull { it.trim().blankToNull() }
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = "DJ") }

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

        /** The acts after the last `mit DJ`: `Rillenrodeo @ Tönz mit DJ gina d`orio & DJ johnboyadonis`. */
        val DJ_TAIL = Regex("""\bmit\s+(DJs?\s+.+)$""", RegexOption.IGNORE_CASE)
        val DJ_SEPARATOR = Regex("""\s+(?:&|und|and)\s+|,\s*""", RegexOption.IGNORE_CASE)
    }
}
