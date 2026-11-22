package de.norm.events.scraper.goldengate

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_LONG_DATE
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.dateOrNull
import de.norm.events.scraper.dateTitleId
import de.norm.events.scraper.goldengate.GoldenGateOverviewPageScraper.Companion.DATE_LINE_PATTERN
import de.norm.events.scraper.goldengate.GoldenGateOverviewPageScraper.Companion.RANGE_LINE_PATTERN
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
 * **content**: a heading matching [DATE_LINE_PATTERN] or [RANGE_LINE_PATTERN] opens a night, the
 * next two are its title and lineup, the next date heading closes it. That also skips the trailing non-event headings
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
        val dateLines = headings.withIndex().mapNotNull { (index, heading) -> DateLine.of(index, heading.text()) }
        val confirmedDates =
            dateLines.flatMap { listOfNotNull(it.first, it.last) }.mapNotNull { day ->
                day.date?.takeIf { day.weekday == null || it.dayOfWeek == day.weekday }
            }
        val events =
            dateLines.mapNotNull { line ->
                resolveDate(line, line.first, confirmedDates)?.let { eventDate ->
                    val endDate = line.last?.let { resolveDate(line, it, confirmedDates) }?.takeIf { it > eventDate }
                    parseNight(line, eventDate, endDate, headings, baseUrl)
                }
            }
        if (dateLines.isEmpty()) warnOnUnopenedRoster(headings)
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
        day: PrintedDay,
        confirmedDates: List<LocalDate>
    ): LocalDate? {
        val date = day.date
        if (date == null) {
            logger.warn { "Unparseable Golden Gate date line '${line.text}', skipping night" }
            return null
        }
        return dateCheckedAgainstWeekday(date, day.weekday, line.text) { candidate ->
            confirmedDates.any { abs(ChronoUnit.DAYS.between(candidate, it)) <= BLOCK_SPAN_DAYS }
        }
    }

    /**
     * A page with a DJ roster and no heading this parser reads as a date stores nothing, and a
     * quiet empty run looks like a block between two announcements. The heading two before the
     * first roster is where the date line stands, so the line names it.
     */
    private fun warnOnUnopenedRoster(headings: List<Element>) {
        val rosterIndex = headings.indexOfFirst { it.textLines().size >= MIN_ROSTER_LINES }
        if (rosterIndex < 0) return
        val heading = headings.getOrNull(rosterIndex - 2)?.text().orEmpty()
        logger.at(Level.WARN) {
            message = "Golden Gate page holds a DJ roster but no date line opens a night; heading before it: '$heading'"
            payload = mapOf(LogFields.DATE_HEADING to heading)
        }
    }

    /**
     * One night from its date line and the two headings after it in [headings], its title and its
     * lineup. Each is used only when *not* itself a date line — a night announced without a lineup
     * (or as the last heading) yields no artists rather than absorbing the next night's date.
     */
    private fun parseNight(
        line: DateLine,
        eventDate: LocalDate,
        endDate: LocalDate?,
        headings: List<Element>,
        baseUrl: String
    ): ScrapedEvent? {
        val title = nonDateHeading(headings.getOrNull(line.index + 1))?.text().blankToNull()
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
            endDate = endDate,
            startTime = parseTime(line.doorTime),
            // No per-event page, so every night points at the homepage and takes its identity from date
            // plus slugified title.
            sourceUrl = baseUrl,
            sourceId = EventSource.GOLDEN_GATE.dateTitleId(eventDate, title),
            artists = parseLineup(nonDateHeading(headings.getOrNull(line.index + 2)))
        )
    }

    /** The heading itself, or `null` when it is the *next* night's date line rather than this night's content. */
    private fun nonDateHeading(heading: Element?): Element? = heading?.takeUnless { isDateHeading(it.text()) }

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
            .flatMap { it.split(SPACED_SLASH) }
            .flatMap(::splitSegmentOnConjunctions)
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = ArtistRole.DJ) }

    /** A printed day of a date heading: the date read from it, and the weekday printed beside it. */
    private data class PrintedDay(
        val date: LocalDate?,
        val weekday: DayOfWeek?
    )

    /**
     * A date heading: its position in the heading stream, its text, and the parts read from it.
     * [last] is set only for a range heading, which names the block's first and last day.
     */
    private data class DateLine(
        val index: Int,
        val text: String,
        val first: PrintedDay,
        val last: PrintedDay?,
        val doorTime: String?
    ) {
        companion object {
            /** The heading read as a single night or a range, or `null` when it is neither. */
            fun of(
                index: Int,
                text: String
            ): DateLine? {
                DATE_LINE_PATTERN.find(text)?.let { match ->
                    val day = PrintedDay(parseLongGermanDate(match.groupValues[2]), parseGermanWeekdayAbbreviation(match.groupValues[1]))
                    return DateLine(index, text, day, last = null, doorTime = match.groupValues[3].blankToNull())
                }
                return RANGE_LINE_PATTERN.find(text)?.let { match ->
                    fun group(name: String) = match.groups[name]?.value.orEmpty()
                    val lastDate = parseLongGermanDate(group("lastDate"))
                    DateLine(
                        index = index,
                        text = text,
                        first = PrintedDay(lastDate?.let { firstDayOf(group("firstDay").toInt(), it) }, parseGermanWeekdayAbbreviation(group("firstWeekday"))),
                        last = PrintedDay(lastDate, parseGermanWeekdayAbbreviation(group("lastWeekday"))),
                        doorTime = group("doorTime").blankToNull()
                    )
                }
            }
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

        /**
         * A range heading — `"Do.19 - So. 22. November 2026"`, one heading for a block of several
         * nights, as the anniversary weekend printed (#5401). It is one row, from the first day to the
         * last ([ScrapedEvent.endDate]). Its named groups are the first weekday and day, the last weekday
         * and full date, and the optional door time. Anchored like [DATE_LINE_PATTERN].
         */
        private val RANGE_LINE_PATTERN =
            Regex(
                """^\s*(?<firstWeekday>\p{L}{2,3})\.?\s*(?<firstDay>\d{1,2})\.?\s*[-–—]\s*""" +
                    """(?<lastWeekday>\p{L}{2,3})\.?\s*(?<lastDate>\d{1,2}\.\s+\p{L}+\s+\d{4})(?:\s*[-–—]\s*(?<doorTime>\d{1,2}:\d{2}))?"""
            )

        /** A heading with this many lines or more is a DJ roster; the page's other headings have one or two. */
        private const val MIN_ROSTER_LINES = 3

        /** How far a corrected date may sit from a confirmed date; the block runs Thursday to Saturday. */
        private const val BLOCK_SPAN_DAYS = 6L

        /** Whether [text] is a date heading of either shape, the one test that opens a night. */
        private fun isDateHeading(text: String) = DATE_LINE_PATTERN.containsMatchIn(text) || RANGE_LINE_PATTERN.containsMatchIn(text)

        /** Parses the German day/month/year part of a date heading, or `null` when it is not one. */
        private fun parseLongGermanDate(text: String): LocalDate? = dateOrNull { LocalDate.parse(text.trim(), GERMAN_LONG_DATE) }

        /** The range's first [day], in the month of its [last] day, or in the month before when [day] is the larger. */
        private fun firstDayOf(
            day: Int,
            last: LocalDate
        ): LocalDate? = dateOrNull { (if (day <= last.dayOfMonth) last else last.minusMonths(1)).withDayOfMonth(day) }
    }
}

/** Two DJs on one line: `Gingerblack / Sharhok Dini`. Unspaced, a slash stays inside a name. */
private val SPACED_SLASH = Regex("""\s+/\s+""")
