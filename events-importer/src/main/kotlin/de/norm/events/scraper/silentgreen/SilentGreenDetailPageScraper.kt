package de.norm.events.scraper.silentgreen

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure HTML parser for a silent green **detail page** (`/programm/detail/<slug>`).
 *
 * A detail page describes a *run*, not one night: the calendar links every open day of an
 * exhibition to the same page, and the page's own date block states the run's span
 * (`"Fr. 17.07.2026 – So. 23.08.2026"`). So it is read for the three fields the calendar row
 * omits — the doors time, the poster and the full blurb — and never for the date, which
 * [SilentGreenMonthPageScraper] takes per day from the calendar.
 *
 * The poster comes from `og:image` rather than the header carousel: the carousel serves a
 * responsive `<picture>` with ten relative sources per slide, while the meta tag names one
 * absolute URL for the same image.
 *
 * @see SilentGreenWebsiteImporter for the HTTP fetch orchestrator.
 */
class SilentGreenDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the run-level fields of a detail page, or `null` when the page carries none of them
     * (e.g. a redirect or an error page served with a 200).
     */
    fun scrape(document: Document): SilentGreenEventDetails? {
        val details =
            SilentGreenEventDetails(
                doorsTime = parseLeadingTime(document, ".event-detail-time-entry"),
                startTime = parseLeadingTime(document, ".event-detail-time-begin"),
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
     * Reads the `HH:mm` that opens a time cell, ignoring the label the venue appends to it —
     * `"19:00 Einlass"`, `"19:45 Beginn"`, and the `"14:00 -"` of an event stated as a span.
     */
    private fun parseLeadingTime(
        document: Document,
        cssQuery: String
    ): LocalTime? = parseTime(document.textAt(cssQuery)?.take(HH_MM_LENGTH))

    /**
     * Joins the prose paragraphs of the detail body into the description.
     *
     * The venue opens most bodies with the same `"… präsentiert"` credit line the calendar prints
     * under the title; it is already captured as the event's promoters, so it is dropped here
     * rather than repeated as the first sentence of the blurb.
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

        /** Characters in an `HH:mm` time, sliced off the front of a labelled time cell. */
        const val HH_MM_LENGTH = 5
    }
}

/**
 * The run-level fields shared by every day of one silent green programme entry.
 *
 * Applied to each calendar occurrence by [applyTo]. Every field is a **fallback**: the calendar
 * row is the per-day truth, so a start time it already carries is kept, and the detail page only
 * fills what the row left empty.
 */
data class SilentGreenEventDetails(
    /** Time the doors open ("Einlass"), which the calendar never shows. */
    val doorsTime: LocalTime? = null,
    /** Time the show starts ("Beginn"), a fallback for a row whose time cell is empty. */
    val startTime: LocalTime? = null,
    /** The full blurb, minus its leading credit line. */
    val description: String? = null,
    /** The event's poster, from the page's `og:image`. */
    val imageUrl: String? = null
) {
    /** Returns [event] with the fields the calendar row could not supply filled in from this run's page. */
    fun applyTo(event: ScrapedEvent): ScrapedEvent =
        event.copy(
            doorsTime = event.doorsTime ?: doorsTime,
            startTime = event.startTime ?: startTime,
            description = event.description ?: description,
            imageUrl = event.imageUrl ?: imageUrl
        )
}
