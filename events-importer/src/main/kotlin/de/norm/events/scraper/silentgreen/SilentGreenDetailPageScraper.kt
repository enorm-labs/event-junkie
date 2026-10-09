package de.norm.events.scraper.silentgreen

import de.norm.events.event.EventType
import de.norm.events.scraper.HH_MM_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.endOn
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for a silent green detail page (`/programm/detail/<slug>`), which describes a
 * run, not one night: the calendar links every open day of an exhibition to the same page,
 * whose date block states the span (`"Fr. 17.07.2026 – So. 23.08.2026"`). Read for the three
 * fields the calendar row omits (doors time, poster, full blurb), never for the date, which
 * [SilentGreenMonthPageScraper] takes per day. The poster comes from `og:image`: the header
 * carousel serves a responsive `<picture>` with ten relative sources per slide, the meta tag
 * one absolute URL.
 *
 * A multi-day festival leaves the time cell empty and prints its daily hours as bold lines at the
 * end of the blurb (`8. Oktober: 19–22 Uhr`, `10.+11. Oktober: 11–24 Uhr`). [dailyHours] reads
 * them as a per-day fallback. A programme highlight in the same blurb (`10. Oktober / 11 bis 18
 * Uhr`) has no colon after the month, so it does not match: it is one screening, not the opening.
 *
 * @see SilentGreenWebsiteImporter for the HTTP fetch orchestrator.
 */
class SilentGreenDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the run-level fields, or `null` when the page carries none (a redirect or error page
     * served with a 200).
     */
    fun scrape(document: Document): SilentGreenEventDetails? {
        val runStart = parseBlockDate(document, ".event-detail-date-begin")
        val details =
            SilentGreenEventDetails(
                doorsTime = parseLeadingTime(document, ".event-detail-time-entry"),
                startTime = parseLeadingTime(document, ".event-detail-time-begin"),
                runStart = runStart,
                runEnd = parseBlockDate(document, ".event-detail-date-end"),
                dailyHours = runStart?.let { dailyHours(document, it) }.orEmpty(),
                description = description(document),
                imageUrl = document.attrAt("meta[property=og:image]", "content")?.takeIf { it.startsWith("http") }
            )

        if (details == SilentGreenEventDetails()) {
            logger.warn { "silent green detail page carries no times, description or image" }
            return null
        }
        return details
    }

    /**
     * The `HH:mm` that opens a time cell, ignoring the appended label (`"19:00 Einlass"`, `"19:45
     * Beginn"`, the `"14:00 -"` of a span).
     */
    private fun parseLeadingTime(
        document: Document,
        cssQuery: String
    ): LocalTime? = parseTime(document.textAt(cssQuery)?.take(HH_MM_LENGTH))

    /**
     * The `DD.MM.YYYY` in one half of the date block (`"Fr. 17.07.2026 -"`, `"So. 23.08.2026"`);
     * weekday and trailing dash are typography.
     */
    private fun parseBlockDate(
        document: Document,
        cssQuery: String
    ): LocalDate? =
        BLOCK_DATE.find(document.textAt(cssQuery).orEmpty())?.value?.let {
            runCatching { LocalDate.parse(it, BLOCK_DATE_FORMAT) }.getOrNull()
        }

    /**
     * The opening hours per day from the blurb's `<days> <Monat>: HH[:mm]–HH[:mm] Uhr` lines. The
     * year is the date block's, plus one for a month before the block's first month. `24` is
     * midnight, so the end falls on the next day.
     */
    private fun dailyHours(
        document: Document,
        runStart: LocalDate
    ): Map<LocalDate, SilentGreenOpeningHours> {
        val text = document.select(BODY_SELECTOR).text()
        return DAILY_HOURS
            .findAll(text)
            .toList()
            .flatMap { match ->
                val (days, monthName) = match.destructured
                val month = parseGermanMonthAbbreviation(monthName.take(MONTH_ABBREVIATION_LENGTH))
                val clocks = match.groupValues.drop(FIRST_CLOCK_GROUP)
                val start = clock(clocks[0], clocks[1])
                val end = clock(clocks[2], clocks[3])
                if (month == null || start == null || end == null) return@flatMap emptyList()
                val year = if (month < runStart.month) runStart.year + 1 else runStart.year
                dayNumbers(days).mapNotNull { day ->
                    runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { it to SilentGreenOpeningHours(start, end) }
                }
            }.toMap()
    }

    /** Expands `10.+11.` to both days and `10.–12.` to the range. */
    private fun dayNumbers(days: String): List<Int> {
        var previous: Int? = null
        return DAY_TOKEN.findAll(days).toList().flatMap { token ->
            val (separator, number) = token.destructured
            val day = number.toInt()
            val from = previous?.takeIf { separator.isNotBlank() && separator != "+" }?.plus(1) ?: day
            previous = day
            (from..day).toList()
        }
    }

    /** `HH[:mm]` as a clock, with `24` as midnight. */
    private fun clock(
        hour: String,
        minute: String
    ): LocalTime? = runCatching { LocalTime.of(hour.toInt() % HOURS_PER_DAY, minute.ifEmpty { "0" }.toInt()) }.getOrNull()

    /**
     * Joins the prose paragraphs into the description, dropping the `"… präsentiert"` credit line
     * most bodies open with, since it is already the promoters. The English page words it
     * `"… presents"`.
     */
    fun description(document: Document): String? =
        document
            .select(BODY_TEXT_SELECTOR)
            .map { it.text().trim() }
            .filter { it.isNotBlank() && silentGreenPresenters(it).isEmpty() && !ENGLISH_CREDIT_LINE.matches(it) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    private companion object {
        /** The detail article's prose, scoped so the page's navigation and footer stay out. */
        const val BODY_SELECTOR = ".news-detail .ce-bodytext"
        const val BODY_TEXT_SELECTOR = "$BODY_SELECTOR p"

        const val HOURS_PER_DAY = 24

        /** The group in [DAILY_HOURS] that holds the start hour; the start minute, end hour and end minute follow. */
        const val FIRST_CLOCK_GROUP = 3

        /** `Oktober` → `okt`, the key [parseGermanMonthAbbreviation] reads. */
        const val MONTH_ABBREVIATION_LENGTH = 3

        /** `8. Oktober: 19–22 Uhr`, `10.+11. Oktober: 11–24 Uhr`; the colon after the month is required. */
        val DAILY_HOURS =
            Regex(
                """(?<![\d.+–-])(\d{1,2}\.(?:\s*[+–-]\s*\d{1,2}\.)*)\s*(\p{L}+)\s*:\s*""" +
                    """(\d{1,2})(?::(\d{2}))?\s*[–-]\s*(\d{1,2})(?::(\d{2}))?\s*Uhr""",
                RegexOption.IGNORE_CASE
            )

        /** One day of a day list, with the `+` or dash in front of it. */
        val DAY_TOKEN = Regex("""([+–-]?)\s*(\d{1,2})\.""")

        /** The English page's credit line, `"silent green presents"`. */
        val ENGLISH_CREDIT_LINE = Regex("""^.{2,80}?\s+presents?$""", RegexOption.IGNORE_CASE)

        /** The date inside a date-block cell. */
        val BLOCK_DATE = Regex("""\d{2}\.\d{2}\.\d{4}""")
        val BLOCK_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}

/**
 * The run-level fields shared by every day of one entry, applied by [applyTo]. Every field is a
 * fallback: the calendar row is the per-day truth.
 */
data class SilentGreenEventDetails(
    /** Time the doors open ("Einlass"), which the calendar never shows. */
    val doorsTime: LocalTime? = null,
    /** Time the show starts ("Beginn"), a fallback for a row whose time cell is empty. */
    val startTime: LocalTime? = null,
    /** First day of the page's date block; the run's opening for an exhibition (ADR-029). */
    val runStart: LocalDate? = null,
    /** Last day of the page's date block; absent on a one-day page. */
    val runEnd: LocalDate? = null,
    /** A festival's opening hours per day, from the blurb; a fallback below both time cells. */
    val dailyHours: Map<LocalDate, SilentGreenOpeningHours> = emptyMap(),
    /** The full blurb, minus its leading credit line. */
    val description: String? = null,
    /** The event's poster, from the page's `og:image`. */
    val imageUrl: String? = null
) {
    /**
     * Returns [event] with the fields the calendar row could not supply filled from this page. An
     * exhibition also takes the page's span: the calendar lists only the days inside the scraped
     * months. The days then fold in [collapseExhibitionRuns]; a festival's days keep their dates.
     * A day's [dailyHours] apply only when neither time cell gives a start.
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent {
        val run = event.eventType == EventType.EXHIBITION.name && runStart != null && runEnd != null && runEnd > runStart
        val hours = if (run) null else openingHours(event)
        return event.copy(
            free = isFree(event),
            doorsTime = event.doorsTime ?: doorsTime,
            startTime = event.startTime ?: startTime ?: hours?.start,
            eventDate = if (run) runStart else event.eventDate,
            endDate = if (run) runEnd else event.endDate ?: hours?.endDate(event.eventDate),
            endTime = event.endTime ?: hours?.end,
            description = event.description ?: description,
            imageUrl = event.imageUrl ?: imageUrl
        )
    }

    /**
     * Whether the row is free, or the blurb's "Eintritt frei" covers the whole event: not when the
     * row links a ticket shop or the blurb names a euro amount. The Festival of Animation does both,
     * for one free screening.
     */
    private fun isFree(event: ScrapedEvent): Boolean {
        val blurb = event.description ?: description
        return event.free || (event.ticketUrl == null && hasFreeEntryPhrase(blurb) && euroAmounts(blurb).isEmpty())
    }

    /** The day's [dailyHours], unless a time cell already gives the start or the end. */
    private fun openingHours(event: ScrapedEvent): SilentGreenOpeningHours? =
        dailyHours[event.eventDate].takeIf { event.startTime == null && startTime == null && event.endTime == null }
}

/** One day's opening hours; an [end] at or before [start] falls on the next day. */
data class SilentGreenOpeningHours(
    val start: LocalTime,
    val end: LocalTime
) {
    /** The day the hours that open on [day] end. */
    fun endDate(day: LocalDate): LocalDate = endOn(day, start, end)
}
