package de.norm.events.scraper.kiezkapelle

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.PRESENTS_WORDS
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.promoterFromCredit
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for the Kiezkapelle's hand-laid Lay Theme programme page. An upcoming entry is one
 * grid row: a column of date lines (`Samstag`, `17. Oktober 2026`, `15:00 - 17:00 Uhr`) beside a
 * column of `h1` headings, the last of them the title and an earlier `X präsentiert:` the
 * presenter. The row before holds the picture and the row after the text.
 *
 * The archive under "Veranstaltungen aus der Vergangenheit" is free-form and never read.
 * Walks, workshops and yoga are dropped (EVENT_SCOPE.md §3.2), and so is an entry that meets
 * off the premises (`Treffpunkt …`).
 */
class KiezkapelleProgrammPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("div.row").takeWhile { !ARCHIVE_HEADING.containsMatchIn(it.text()) }
        val entries = rows.filter { row -> row.selectFirst("h1") != null && row.select("p").any { LONG_DATE.containsMatchIn(it.text()) } }
        logger.info { "Found ${entries.size} upcoming entries on the Kiezkapelle programme" }
        return entries.mapSkippingFailures(logger, "a Kiezkapelle programme entry") { parseEntry(it, sourceUrl) }
    }

    private fun parseEntry(
        row: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val lines = row.select("p").mapNotNull { it.text().clean().blankToNull() }
        val headings = row.select("h1").mapNotNull { it.text().clean().blankToNull() }
        val title = headings.lastOrNull() ?: return null
        val date = lines.firstNotNullOfOrNull(::parseLongDate) ?: error("No date in '$title'")
        if (!isInScope(title, lines)) {
            logger.info { "Kiezkapelle entry '$title' on $date is not a stage programme, skipping" }
            return null
        }
        val times = lines.firstNotNullOfOrNull { TIME_RANGE.find(it) }
        return ScrapedEvent(
            title = title,
            eventType = inferUnmarkedTitleType(title),
            eventDate = date,
            startTime = times?.let { clock(it.groupValues[1]) },
            endTime = times?.let { clock(it.groupValues[2]) },
            description = row.descriptionAfter(),
            imageUrl =
                row
                    .previousElementSibling()
                    ?.selectFirst("img[data-src]")
                    ?.absUrl("data-src")
                    ?.blankToNull(),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.KIEZKAPELLE.sourceIdPrefix}$date-${SlugGenerator.slugify(title)}",
            promoters = headings.dropLast(1).filter { PRESENTS_CREDIT.containsMatchIn(it) }.mapNotNull(::promoterFromCredit)
        )
    }

    /** The paragraphs of the text-only row that follows an entry, or `null` when the next row is something else. */
    private fun Element.descriptionAfter(): String? =
        nextElementSibling()
            ?.takeIf { it.selectFirst("h1, img") == null }
            ?.select("p")
            ?.mapNotNull { it.text().clean().blankToNull() }
            ?.joinToString("\n\n")
            ?.blankToNull()

    private fun isInScope(
        title: String,
        lines: List<String>
    ): Boolean =
        !PARTICIPATION.containsMatchIn(title) &&
            lines.none { it.startsWith(OFF_SITE_MEETING_POINT, ignoreCase = true) } &&
            !billsChildrensShow(title, null)

    private fun parseLongDate(line: String): LocalDate? {
        val match = LONG_DATE.find(line) ?: return null
        val (day, month, year) = match.destructured
        return try {
            LocalDate.parse("$day. $month $year", LONG_DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun clock(text: String): LocalTime? = parseTime(text.padStart(HH_MM_LENGTH, '0'))

    private fun String.clean(): String = replace(WHITESPACE, " ").trim()

    private companion object {
        val ARCHIVE_HEADING = Regex("""^\s*(?:Veranstaltungen aus der Vergangenheit|Vergangene Veranstaltungen)\s*$""", RegexOption.IGNORE_CASE)

        /** `17. Oktober 2026`; the archive's year-less `25.08.` never matches. */
        val LONG_DATE = Regex("""(\d{1,2})\.\s*(\p{L}+)\s+(\d{4})""")
        val LONG_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)

        /** `15:00 - 17:00 Uhr`; the end is optional. */
        val TIME_RANGE = Regex("""(\d{1,2}:\d{2})(?:\s*[-–]\s*(\d{1,2}:\d{2}))?\s*Uhr""")
        const val HH_MM_LENGTH = 5

        val PRESENTS_CREDIT = Regex("""(?:$PRESENTS_WORDS)\s*:?\s*$""", RegexOption.IGNORE_CASE)

        /** Formats you take part in rather than watch, and church services. */
        val PARTICIPATION =
            Regex("""(?<!\p{L})(?:walk|spaziergang|rundgang|führung|workshop|yoga|gottesdienst|andacht)(?!\p{L})""", RegexOption.IGNORE_CASE)

        const val OFF_SITE_MEETING_POINT = "Treffpunkt"
    }
}
