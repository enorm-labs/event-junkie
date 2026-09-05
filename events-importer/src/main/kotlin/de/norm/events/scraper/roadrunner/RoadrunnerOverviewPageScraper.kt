package de.norm.events.scraper.roadrunner

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Pure HTML parser for Roadrunner's Paradise' retro `programm.html` event page.
 *
 * The site is hand-coded HTML from the early 2000s with **no semantic structure**:
 * every event is a flat run of `<p>` paragraphs, and events are separated by
 * paragraphs containing only a row of dots (". . . . ."). There are no per-event
 * URLs — the whole programme lives on one page.
 *
 * Parsing therefore anchors on the one thing that carries meaning: the **German
 * date line** ("Freitag, 29. Mai:"). An event starts at a date line and runs until
 * the next date line or a dotted separator; the paragraphs in between supply the
 * title, doors time, ticket link, flyer image and description.
 *
 * Dates carry a weekday but **no year**, so the year is inferred from the weekday:
 * among nearby candidate years, the one whose 29 May actually falls on the stated
 * Friday and lands closest to today (see [inferYear]). The venue often leaves stale
 * past events listed; those past-dated events are dropped centrally at persistence
 * time (`EventUpsertService`), so this parser returns every dated block as-is.
 *
 * @see RoadrunnerWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="http://www.roadrunners-paradise.de/programm.html">Roadrunner's Paradise programme</a>
 */
@Suppress("TooManyFunctions") // Cohesive single-responsibility parser; the retro markup needs many small field extractors
class RoadrunnerOverviewPageScraper(
    /** Clock for year inference. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the
     *   relative flyer image path and as each event's `sourceUrl`.
     * @return a list of [ScrapedEvent] instances, one per dated block.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val blocks = splitIntoEventBlocks(document.select("p"))
        logger.info { "Found ${blocks.size} event block(s) on Roadrunner programme" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed blocks without aborting the whole import
        val parsed =
            blocks.mapNotNull { block ->
                try {
                    parseBlock(block, baseUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse event block, skipping" }
                    null
                }
            }

        return parsed
    }

    /**
     * Groups the flat paragraph list into per-event blocks.
     *
     * A [date line][isDateLine] opens a new block; the block then absorbs following
     * paragraphs until the next date line or a [dotted separator][isSeparator].
     * Paragraphs before the first date line (page header) and after the last
     * separator (footer) belong to no block and are dropped.
     */
    @Suppress("DoubleMutabilityForCollection") // The segmenter both starts a block (reassign) and extends it (mutate).
    private fun splitIntoEventBlocks(paragraphs: List<Element>): List<List<Element>> {
        val blocks = mutableListOf<List<Element>>()
        var current: MutableList<Element>? = null

        for (p in paragraphs) {
            when {
                isSeparator(p) -> {
                    current?.let { blocks.add(it) }
                    current = null
                }

                isDateLine(p) -> {
                    current?.let { blocks.add(it) }
                    current = mutableListOf(p)
                }

                else -> {
                    current?.add(p)
                }
            }
        }
        current?.let { blocks.add(it) }
        return blocks
    }

    /** Parses one event block (a date line plus its following paragraphs) into a [ScrapedEvent]. */
    @Suppress("ReturnCount") // Guard clauses for the required date/title are clearer than nesting
    private fun parseBlock(
        block: List<Element>,
        baseUrl: String
    ): ScrapedEvent? {
        val dateLine = block.firstOrNull { isDateLine(it) } ?: return null
        val eventDate =
            parseGermanDate(dateLine.text()) ?: run {
                logger.warn { "Could not parse date from '${dateLine.text()}', skipping block" }
                return null
            }

        val title = parseTitle(block)
        if (title.isNullOrBlank()) {
            logger.warn { "Event on $eventDate has no title, skipping" }
            return null
        }

        val doorsTime = parseDoorsTime(block)
        val ticketUrl = block.firstNotNullOfOrNull { it.selectFirst("a[href^=http]")?.attr("href") }
        // Flyer file names are typed by hand and may carry a space ("Images/Programm/The lazys.jpg"),
        // which URI.resolve rejects — and an unusable flyer must not cost the whole event (#1130).
        val imageUrl =
            block
                .firstNotNullOfOrNull { it.selectFirst("img[src]") }
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { resolveUrl(baseUrl, it.replace(" ", "%20")) }.getOrNull() }
        val description = parseDescription(block, dateLine, title)

        return ScrapedEvent(
            title = title,
            description = description,
            // The retro programme carries no category field; infer from the title
            // (concert by default for this live-music venue). See inferConcertVenueType.
            eventType = inferConcertVenueType(title),
            eventDate = eventDate,
            doorsTime = doorsTime,
            imageUrl = imageUrl,
            // No per-event URLs on this single-page site — the programme page is the source.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.ROADRUNNER.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = ticketUrl
        )
    }

    /** The event title, rendered in the first `Stil11` element, with the plain bold title as a fallback. */
    private fun parseTitle(block: List<Element>): String? =
        block
            .firstNotNullOfOrNull { titleElement(it) }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: block
                .firstNotNullOfOrNull { it.selectFirst("strong") }
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

    /** Doors time from the "Einlass: HH:mm Uhr" paragraph. */
    private fun parseDoorsTime(block: List<Element>): LocalTime? {
        val einlass = block.map { it.text() }.firstOrNull { EINLASS_PATTERN.containsMatchIn(it) } ?: return null
        return parseTime(EINLASS_PATTERN.find(einlass)?.groupValues?.get(1))
    }

    /**
     * Joins the block's prose paragraphs into the description, dropping the
     * structural lines (date, title, "Einlass…", the ticket-link line, and
     * dot separators) and image-only paragraphs.
     */
    private fun parseDescription(
        block: List<Element>,
        dateLine: Element,
        title: String
    ): String? =
        block
            .asSequence()
            .filter { it !== dateLine }
            .filter { titleElement(it) == null } // title line
            .filter { it.selectFirst("img") == null } // flyer-only line
            .filter { it.selectFirst("a[href^=http]") == null } // ticket-link line
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !isDotsOnly(it) }
            .filterNot { EINLASS_PATTERN.containsMatchIn(it) }
            .filterNot { it == title }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    // -- Date parsing & year inference ------------------------------------

    /**
     * Parses a German date line like "Freitag, 29. Mai:" into a [LocalDate].
     *
     * The day and month come from the text; the year is [inferred][inferYear] from
     * the weekday. Returns `null` when the day/month cannot be parsed.
     */
    private fun parseGermanDate(text: String): LocalDate? {
        val match = DATE_PATTERN.find(text) ?: return null
        val (weekdayName, day, month) = match.destructured
        val weekday = GERMAN_WEEKDAYS[weekdayName.lowercase()]
        return parseGermanMonthDay(day, month)?.let { inferYearForWeekday(it, weekday, clock) }
    }

    /** Parses "29" + "Mai" (or a zero-padded "05") into a [MonthDay], or `null` when unparseable. */
    private fun parseGermanMonthDay(
        day: String,
        month: String
    ): MonthDay? = runCatching { MonthDay.parse("${day.toInt()}. $month", GERMAN_DAY_MONTH_FORMATTER) }.getOrNull()

    // -- Line classification ----------------------------------------------

    /**
     * The `Stil11` title styling, which the hand-coded page puts on a `<span>` inside the paragraph
     * on some blocks and on the `<p>` itself on others (autumn 2026: "BLUT & EISEN 30 JAHRE").
     */
    private fun titleElement(p: Element): Element? = if (p.hasClass(TITLE_CLASS)) p else p.selectFirst("span.$TITLE_CLASS")

    private fun isDateLine(p: Element): Boolean = DATE_PATTERN.containsMatchIn(p.text())

    /** A separator paragraph is non-empty and made up solely of dots and whitespace. */
    private fun isSeparator(p: Element): Boolean = isDotsOnly(p.text())

    private fun isDotsOnly(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.all { it == '.' || it.isWhitespace() }
    }

    companion object {
        /** The stylesheet class the page's author gives an event's title line. */
        private const val TITLE_CLASS = "Stil11"

        /**
         * Matches a German date line — "<Weekday>, <day>. <Month>" — capturing the weekday, day
         * number and month name. The comma and the day's dot are optional: the page is typed by
         * hand and one season wrote "Samstag 05 September:" and "Samstag 31 Oktober:", which the
         * strict form silently turned into an import of zero events (#1130). The trailing colon
         * and any following text (e.g. a second date for two-day events) are ignored.
         */
        private val DATE_PATTERN =
            Regex(
                """(Montag|Dienstag|Mittwoch|Donnerstag|Freitag|Samstag|Sonntag),?\s*(\d{1,2})\.?\s*""" +
                    """(Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)""",
                RegexOption.IGNORE_CASE
            )

        /** Extracts "Einlass: HH:mm" (the "Uhr" suffix is ignored). */
        private val EINLASS_PATTERN = Regex("""Einlass:\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        private val GERMAN_WEEKDAYS: Map<String, DayOfWeek> =
            mapOf(
                "montag" to DayOfWeek.MONDAY,
                "dienstag" to DayOfWeek.TUESDAY,
                "mittwoch" to DayOfWeek.WEDNESDAY,
                "donnerstag" to DayOfWeek.THURSDAY,
                "freitag" to DayOfWeek.FRIDAY,
                "samstag" to DayOfWeek.SATURDAY,
                "sonntag" to DayOfWeek.SUNDAY
            )

        /** Parses "29. Mai" using full German month names, case-insensitively. */
        private val GERMAN_DAY_MONTH_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("d. MMMM")
                .toFormatter(Locale.GERMAN)
    }
}
