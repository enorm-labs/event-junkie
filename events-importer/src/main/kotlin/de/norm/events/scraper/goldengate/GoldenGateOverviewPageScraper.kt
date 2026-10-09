package de.norm.events.scraper.goldengate

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.goldengate.GoldenGateOverviewPageScraper.Companion.DATE_LINE_PATTERN
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Pure HTML parser for Golden Gate Berlin's homepage programme.
 *
 * The club announces only the **current Thursday–Saturday block** — three nights, each an
 * Elementor container holding exactly three headings:
 *
 * ```
 * Do. 30. Juli 2026 - 23:59      ← date line: weekday, day, German month, year, door time
 * Donnerdogge                     ← the night's name
 * Neco<br>Jeremy Reinhard<br>…    ← the DJ roster
 * ```
 *
 * Elementor names every element with a per-element hash (`elementor-element-7b3297a`) that
 * changes on every edit, and the theme adds no venue-semantic classes, so nothing stable
 * anchors a container selector. This parser walks the `.elementor-heading-title` headings —
 * Elementor's *widget-type* class, stable across edits — as one ordered stream keyed off
 * **content**: a heading matching [DATE_LINE_PATTERN] opens a night, the next two are its title
 * and lineup, the next date heading closes it. That also skips the trailing non-event headings
 * ("Tickets only available at the door.", "enter", "SHOPPING") without enumerating them.
 *
 * The date lines are typed by hand, and the weekday is the check on them: "Sa. 03. September
 * 2026" stood for Saturday 3 October (#2349). [resolveDate] moves a night to the month its
 * weekday names only when that month fits the block.
 *
 * Passed nights stay on the page until the block rolls over; parsed here, dropped centrally at
 * persistence by `EventUpsertService`[de.norm.events.scraper.EventUpsertService], so an import
 * late in the week legitimately stores as little as one event.
 *
 * @see GoldenGateWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://goldengate-berlin.de/">Golden Gate Berlin</a>
 */
class GoldenGateOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the announced nights from the homepage, in listing order.
     *
     * @param baseUrl the URL the document was fetched from, stored as each event's
     * [ScrapedEvent.sourceUrl] — no per-event page.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val headings = document.select(".elementor-heading-title")
        val dateLines =
            headings.withIndex().mapNotNull { (index, heading) ->
                DATE_LINE_PATTERN.find(heading.text())?.let { DateLine.of(index, heading.text(), it) }
            }
        val confirmedDates = dateLines.mapNotNull { line -> line.date?.takeIf { line.weekday == null || it.dayOfWeek == line.weekday } }
        val events =
            dateLines.mapNotNull { line ->
                resolveDate(line, confirmedDates)?.let { eventDate ->
                    parseNight(line, eventDate, headings.getOrNull(line.index + 1), headings.getOrNull(line.index + 2), baseUrl)
                }
            }
        logger.info { "Scraped ${events.size} night(s) from Golden Gate homepage" }
        return events
    }

    /**
     * The night's date, checked against the weekday printed beside it ([dateCheckedAgainstWeekday]).
     * A neighbouring month replaces the date only when it sits within [BLOCK_SPAN_DAYS] of a date
     * in the block whose weekday agrees.
     */
    private fun resolveDate(
        line: DateLine,
        confirmedDates: List<LocalDate>
    ): LocalDate? {
        val date = line.date
        if (date == null) {
            logger.warn { "Unparseable Golden Gate date line '${line.text}', skipping night" }
            return null
        }
        return dateCheckedAgainstWeekday(date, line.weekday, line.text) { candidate ->
            confirmedDates.any { abs(ChronoUnit.DAYS.between(candidate, it)) <= BLOCK_SPAN_DAYS }
        }
    }

    /**
     * One night from its date line and the two headings after it. [titleHeading] and
     * [lineupHeading] are used only when *not* themselves a date line — a night announced without
     * a lineup (or as the last heading) yields no artists rather than absorbing the next night's date.
     */
    private fun parseNight(
        line: DateLine,
        eventDate: LocalDate,
        titleHeading: Element?,
        lineupHeading: Element?,
        baseUrl: String
    ): ScrapedEvent? {
        val title = nonDateHeading(titleHeading)?.text()?.trim()?.takeIf { it.isNotBlank() }
        if (title == null) {
            logger.warn { "No title heading after Golden Gate date line '${line.text}', skipping night" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // A techno club whose whole programme is DJ nights, emitting no category, so the type is
            // fixed rather than inferred from the night's name.
            eventType = EventType.PARTY.name,
            // The venue names no style but programmes techno and house, so the venue is the default, as at Tresor.
            eventDate = eventDate,
            startTime = parseTime(line.doorTime),
            // No per-event page, so every night points at the homepage and takes its identity from date
            // plus slugified title.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.GOLDEN_GATE.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            artists = parseLineup(nonDateHeading(lineupHeading))
        )
    }

    /** The heading itself, or `null` when it is the *next* night's date line rather than this night's content. */
    private fun nonDateHeading(heading: Element?): Element? = heading?.takeUnless { DATE_LINE_PATTERN.containsMatchIn(it.text()) }

    /**
     * The DJs billed for the night, in listing order: one heading, names `<br>`-separated, each
     * line split at safe `&`/`and`/`und` boundaries ([splitSegmentOnConjunctions]), so a
     * back-to-back billing ("Nyna Curtis & Kisling") becomes two DJs while a name whose spelling contains a conjunction tail
     * stays whole. A `b2b` slot splits at the import boundary. Placeholders and non-artists are dropped.
     */
    private fun parseLineup(lineupHeading: Element?): List<ScrapedArtist> =
        lineupHeading
            ?.textLines()
            .orEmpty()
            .flatMap(::splitSegmentOnConjunctions)
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "DJ") }

    /** A date heading: its position in the heading stream, its text, and the parts read from it. */
    private data class DateLine(
        val index: Int,
        val text: String,
        val date: LocalDate?,
        val weekday: DayOfWeek?,
        val doorTime: String?
    ) {
        companion object {
            fun of(
                index: Int,
                text: String,
                match: MatchResult
            ) = DateLine(
                index = index,
                text = text,
                date = parseLongGermanDate(match.groupValues[2]),
                weekday = parseGermanWeekdayAbbreviation(match.groupValues[1]),
                doorTime = match.groupValues[3].takeIf { it.isNotBlank() }
            )
        }
    }

    private companion object {
        /**
         * A date heading — `"Do. 30. Juli 2026 - 23:59"`. Captures the weekday (group 1), the date
         * (group 2) and the optional door time (group 3). Anchored at the start so a heading merely
         * mentioning a date in prose cannot open a night.
         */
        private val DATE_LINE_PATTERN =
            Regex("""^\s*(\p{L}{2,3})\.?\s+(\d{1,2}\.\s+\p{L}+\s+\d{4})(?:\s*[-–—]\s*(\d{1,2}:\d{2}))?""")

        /** How far a corrected date may sit from a confirmed date; the block runs Thursday to Saturday. */
        private const val BLOCK_SPAN_DAYS = 6L

        /** The date format inside a heading, e.g. "30. Juli 2026". */
        private val GERMAN_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)

        /** Parses the German day/month/year part of a date heading, or `null` when it is not one. */
        private fun parseLongGermanDate(text: String): LocalDate? =
            try {
                LocalDate.parse(text.trim(), GERMAN_DATE_FORMATTER)
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
