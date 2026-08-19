package de.norm.events.scraper.heidegluehen

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Heideglühen's `/monatsvorschau/` page — the open-air's whole published
 * programme, which is **one month at a time**.
 *
 * The site has no per-event pages and no archive: a single WordPress (Beaver Builder) rich-text
 * block lists the month's Saturdays as `<p>` paragraphs separated by `~~~`, and is replaced
 * wholesale at the end of each month. Four or five dates is therefore the venue's entire output,
 * not a truncated import.
 *
 * Each event paragraph mixes two kinds of line in **no fixed order**: a German prose date
 * ("Samstag, 1. August 2026, 12 Uhr (bis Sonntag, 6 Uhr)") and one or more highlighted `<mark>`
 * names. A plain week reads date-then-name; the anniversary weekend reads name-then-date-then-name.
 * Lines are therefore classified by what they are rather than by where they sit: the one that
 * parses as a date is the date, the first `<mark>` is the title and any later `<mark>` is the
 * subtitle ("34-Stunden-Weekender").
 *
 * The party runs overnight — the paragraph's closing time belongs to the *next* day — and the model
 * stores no end time, so that tail is kept as the description instead of being dropped.
 *
 * @see HeidegluehenWeekPageScraper for the imminent event's DJ lineup.
 * @see HeidegluehenWebsiteImporter for the HTTP fetch orchestrator.
 */
class HeidegluehenMonthPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event paragraph on the month page.
     *
     * @param sourceUrl the URL the document was fetched from, stored as every event's `sourceUrl` —
     *   the venue publishes no per-event page to link to.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        // The month's artwork covers all of its dates. The selector has to be this specific: the
        // page also carries the site logo (outside the content column) and a close button (inside
        // it, but right-aligned), and only the artwork is a centred photo within `#pagecontent`.
        val monthImageUrl = document.imgSrcAt(MONTH_ARTWORK)

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed paragraphs without aborting the import
        val events =
            document.select(".fl-rich-text p").mapNotNull { paragraph ->
                try {
                    parseParagraph(paragraph, sourceUrl, monthImageUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Heideglühen paragraph, skipping" }
                    null
                }
            }
        logger.info { "Found ${events.size} event(s) on the Heideglühen month page" }
        return events
    }

    /**
     * Parses one paragraph into a [ScrapedEvent], or `null` when it holds no date — the separator
     * paragraphs, the month's shared "Unter anderem mit" name-drop and the footer notes all fail
     * that test, which is what keeps them out of the programme.
     */
    @Suppress("ReturnCount") // Guard clauses for the required date and title are clearer than nesting
    private fun parseParagraph(
        paragraph: Element,
        sourceUrl: String,
        monthImageUrl: String?
    ): ScrapedEvent? {
        val lines = paragraph.textLines()
        val schedule = lines.firstNotNullOfOrNull { parseSchedule(it) } ?: return null

        val marks = paragraph.select("mark").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }.distinct()
        val title = marks.firstOrNull()?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Heideglühen paragraph for ${schedule.date} names no event, skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            subtitle = marks.drop(1).joinToString(" · ").takeIf { it.isNotBlank() },
            // The model stores no end time, and this one matters: the party runs into the next day.
            description = schedule.closingNote,
            eventType = EventType.PARTY.name,
            eventDate = schedule.date,
            startTime = schedule.startTime,
            imageUrl = monthImageUrl,
            sourceUrl = sourceUrl,
            // There is one party per date and no per-event page, so the date is the identity.
            sourceId = "${EventSource.HEIDEGLUEHEN.sourceIdPrefix}${schedule.date}"
        )
    }
}

/**
 * The start of a party and the venue's own wording for when it ends.
 *
 * @property date the day the party starts.
 * @property startTime the hour it opens.
 * @property closingNote the "bis …" tail, kept because the model has nowhere else to put it.
 */
internal data class HeidegluehenSchedule(
    val date: LocalDate,
    val startTime: LocalTime?,
    val closingNote: String?
)

/**
 * Parses one of the venue's prose date lines, or `null` when the line is not one.
 *
 * Both spellings the site uses are accepted: the month page's
 * `"Samstag, 1. August 2026, 12 Uhr (bis Sonntag, 6 Uhr)"` and the week page's
 * `"Samstag, 6. Juni 2026, 12 Uhr,"` with its closing time on the following line.
 */
internal fun parseSchedule(line: String): HeidegluehenSchedule? {
    val groups = GERMAN_PROSE_DATE.find(line)?.groupValues ?: return null
    val month = parseGermanMonthAbbreviation(groups[MONTH_GROUP].take(GERMAN_MONTH_PREFIX))
    val date = month?.let { runCatching { LocalDate.of(groups[YEAR_GROUP].toInt(), it, groups[DAY_GROUP].toInt()) }.getOrNull() }

    return date?.let {
        HeidegluehenSchedule(
            date = it,
            startTime = parseHour(groups[HOUR_GROUP]),
            closingNote =
                CLOSING_NOTE
                    .find(line)
                    ?.value
                    ?.trim('(', ')', ' ')
                    ?.takeIf { note -> note.isNotBlank() }
        )
    }
}

/** Reads the venue's whole-hour opening time, accepting the `24 Uhr` it writes for midnight. */
private fun parseHour(text: String): LocalTime? =
    text
        .toIntOrNull()
        ?.takeIf { it in 0..HOURS_IN_DAY }
        ?.let { LocalTime.of(it % HOURS_IN_DAY, 0) }

/** The month's artwork: the one centred photo inside the page's content column. */
internal const val MONTH_ARTWORK = "#pagecontent .fl-photo-align-center img"

/** `"Samstag, 1. August 2026, 12 Uhr"` — weekday and anything after the hour are ignored. */
private val GERMAN_PROSE_DATE = Regex("""(\d{1,2})\.\s*([A-Za-zÄÖÜäöüß]+)\s+(\d{4}),?\s*(\d{1,2})\s*Uhr""")

/** The `"(bis Sonntag, 6 Uhr)"` tail stating when the party ends. */
private val CLOSING_NOTE = Regex("""\(?\s*bis\s[^)]*\)?""", RegexOption.IGNORE_CASE)

/** Capture groups of [GERMAN_PROSE_DATE]. */
private const val DAY_GROUP = 1
private const val MONTH_GROUP = 2
private const val YEAR_GROUP = 3
private const val HOUR_GROUP = 4

/** German abbreviates every month to three letters, so the full names resolve through their prefix. */
private const val GERMAN_MONTH_PREFIX = 3

private const val HOURS_IN_DAY = 24
