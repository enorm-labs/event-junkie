package de.norm.events.scraper.silentgreen

import de.norm.events.event.EventType
import de.norm.events.scraper.HH_MM_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
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
 * @see SilentGreenWebsiteImporter for the HTTP fetch orchestrator.
 */
class SilentGreenDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the run-level fields, or `null` when the page carries none (a redirect or error page
     * served with a 200).
     */
    fun scrape(document: Document): SilentGreenEventDetails? {
        val details =
            SilentGreenEventDetails(
                doorsTime = parseLeadingTime(document, ".event-detail-time-entry"),
                startTime = parseLeadingTime(document, ".event-detail-time-begin"),
                runStart = parseBlockDate(document, ".event-detail-date-begin"),
                runEnd = parseBlockDate(document, ".event-detail-date-end"),
                description = parseDescription(document),
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
     * Joins the prose paragraphs into the description, dropping the `"… präsentiert"` credit line
     * most bodies open with, since it is already the promoters.
     */
    private fun parseDescription(document: Document): String? =
        document
            .select(BODY_TEXT_SELECTOR)
            .map { it.text().trim() }
            .filter { it.isNotBlank() && silentGreenPresenters(it).isEmpty() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    private companion object {
        /** The detail article's prose, scoped so the page's navigation and footer stay out. */
        const val BODY_TEXT_SELECTOR = ".news-detail .ce-bodytext p"

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
    /** The full blurb, minus its leading credit line. */
    val description: String? = null,
    /** The event's poster, from the page's `og:image`. */
    val imageUrl: String? = null
) {
    /**
     * Returns [event] with the fields the calendar row could not supply filled from this page. An
     * exhibition also takes the page's span: the calendar lists only the days inside the scraped
     * months. The days then fold in [collapseExhibitionRuns]; a festival's days keep their dates.
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent {
        val run = event.eventType == EventType.EXHIBITION.name && runStart != null && runEnd != null && runEnd > runStart
        return event.copy(
            doorsTime = event.doorsTime ?: doorsTime,
            startTime = event.startTime ?: startTime,
            eventDate = if (run) runStart else event.eventDate,
            endDate = if (run) runEnd else event.endDate,
            description = event.description ?: description,
            imageUrl = event.imageUrl ?: imageUrl
        )
    }
}
