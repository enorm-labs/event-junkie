package de.norm.events.scraper.junctionbar

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for Junction Bar Berlin's single-page DJ program (`DJ_html/DJ.html`).
 *
 * The page is retro hand-coded HTML sharing the live-music page's flat layout: inside
 * `div.gridContainer`, each club night starts at a *date bar* — a `<div>` holding a `<table>`
 * whose cell carries a `strong.Datum`/`.Datum-DJ` with a German `DD.MM.` date, an English
 * weekday abbreviation, and a start time. The `p.djane` lines that follow (until the next date
 * bar) hold the night's theme ("black music & classics with") and the DJ name ("DJane B.B.").
 * A "PRIVAT PARTY" night carries no public act and is skipped.
 *
 * Every night is a DJ dance party, so all events are typed [EventType.PARTY]. Dates render
 * year-less; the year is inferred from the weekday abbreviation via [inferYearForWeekday]. The
 * venue leaves recently-passed nights on the page; those are dropped centrally at persistence
 * time (`EventUpsertService`), so this parser returns every dated night as-is.
 *
 * @see JunctionBarDjWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.junction-bar.de/DJ_html/DJ.html">Junction Bar DJ program</a>
 */
@Suppress("TooManyFunctions") // Cohesive single-responsibility parser; the inline markup needs several small field extractors.
class JunctionBarDjOverviewPageScraper(
    /** Clock for weekday-based year inference. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all DJ nights from the program page.
     *
     * @param baseUrl the URL the document was fetched from, used as each event's `sourceUrl`.
     * @return one [ScrapedEvent] per dated night that names a public DJ act.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val container = document.selectFirst("div.gridContainer") ?: document.body()

        val groups = segmentIntoNights(container, ::isDateBar)
        logger.info { "Found ${groups.size} dated night(s) on Junction Bar DJ page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed nights without aborting the whole import.
        return groups.mapNotNull { group ->
            try {
                parseNight(group, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Junction Bar DJ night, skipping" }
                null
            }
        }
    }

    /** A date-bar child holds a `<table>` whose text carries a `DD.MM.` date; content lines carry none. */
    internal fun isDateBar(child: Element): Boolean = child.selectFirst("table") != null && DATE_PATTERN.containsMatchIn(child.text())

    @Suppress("ReturnCount") // Guard clauses for the required date and DJ name are clearer than nesting.
    private fun parseNight(
        night: Night,
        baseUrl: String
    ): ScrapedEvent? {
        val eventDate = parseDate(night.dateBar) ?: return null

        val lines =
            night.content
                .select(".djane")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.equals(PRIVAT_PARTY, ignoreCase = true) }
        if (lines.isEmpty()) return null // "PRIVAT PARTY" or an empty night — nothing public to import.

        val themeIndex = lines.indexOfFirst { it.endsWith(THEME_SUFFIX, ignoreCase = true) }
        val theme =
            lines
                .getOrNull(themeIndex)
                ?.dropLast(THEME_SUFFIX.length)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val djNames = lines.filterIndexed { index, _ -> index != themeIndex }

        val title = djNames.joinToString(TITLE_SEPARATOR).ifBlank { theme ?: return null }

        return ScrapedEvent(
            title = title,
            // The theme line ("black music & classics") is display prose, not a normalizable genre —
            // keep it as a subtitle so it never seeds bogus genre tags.
            subtitle = theme,
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseStartTime(night.dateBar),
            sourceUrl = baseUrl,
            // The "dj-" discriminator namespaces DJ nights within the shared JUNCTION_BAR source so they
            // never collide with a live-music night's synthesized sourceId on the same date.
            sourceId = "${EventSource.JUNCTION_BAR.sourceIdPrefix}dj-$eventDate-${SlugGenerator.slugify(title)}",
            artists =
                djNames
                    .filterNot { isNonArtistName(it) }
                    .map { ScrapedArtist(name = it, role = "DJ") }
        )
    }

    /**
     * The `DD.MM.` date, with the year inferred from the English weekday abbreviation via
     * [inferYearForWeekday]. Returns `null` when the date cannot be parsed.
     */
    @Suppress("ReturnCount") // Null-safe early exits per date component are clearer than nested let-chains.
    private fun parseDate(dateBar: Element): LocalDate? {
        val text = dateBar.text()
        val match = DATE_PATTERN.find(text) ?: return null
        val (day, month) = match.destructured
        val monthDay =
            try {
                MonthDay.of(month.toInt(), day.toInt())
            } catch (_: DateTimeException) {
                return null
            }
        val weekday = ENGLISH_WEEKDAY_ABBREVIATIONS[WEEKDAY_PATTERN.find(text)?.value?.lowercase()]
        return inferYearForWeekday(monthDay, weekday, clock)
    }

    /** The start time from the date bar; the venue's "24:00" (midnight) folds to 00:00. */
    private fun parseStartTime(dateBar: Element): LocalTime? {
        val match = TIME_PATTERN.find(dateBar.text()) ?: return null
        val (hour, minute) = match.destructured
        val normalizedHour = hour.toInt() % HOURS_PER_DAY
        return runCatching { LocalTime.of(normalizedHour, minute.toInt()) }.getOrNull()
    }

    companion object {
        /** The `DD.MM.` date opening a date bar. */
        private val DATE_PATTERN = Regex("""(\d{1,2})\.(\d{1,2})\.""")

        /** The `HH:mm` start time in a date bar (e.g. "24:00", "23:00"). */
        private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")

        /** An English weekday abbreviation (mon–sun) in a date bar. */
        private val WEEKDAY_PATTERN = Regex("""\b(mon|tue|wed|thu|fri|sat|sun)\b""", RegexOption.IGNORE_CASE)

        /** English three-letter weekday abbreviations used in the DJ program. */
        private val ENGLISH_WEEKDAY_ABBREVIATIONS: Map<String, DayOfWeek> =
            mapOf(
                "mon" to DayOfWeek.MONDAY,
                "tue" to DayOfWeek.TUESDAY,
                "wed" to DayOfWeek.WEDNESDAY,
                "thu" to DayOfWeek.THURSDAY,
                "fri" to DayOfWeek.FRIDAY,
                "sat" to DayOfWeek.SATURDAY,
                "sun" to DayOfWeek.SUNDAY
            )

        private const val PRIVAT_PARTY = "PRIVAT PARTY"
        private const val THEME_SUFFIX = "with"
        private const val TITLE_SEPARATOR = " + "
        private const val HOURS_PER_DAY = 24
    }
}
