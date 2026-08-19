package de.norm.events.scraper.badehaus

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate

/**
 * Pure HTML parser for Badehaus Berlin event detail (`/events/<slug>/`) pages.
 *
 * The detail page is the **primary source for the richer fields the listing card
 * omits**: the full description, the start time (`Beginn`) and the promoter
 * (`a.promoterbtn`). It reuses the same `.em-event-single` container the theme
 * renders for a single event and also carries the title, date, doors time, image
 * and ticket link, which serve as fallbacks.
 *
 * The overview page remains authoritative for the fields only it exposes reliably —
 * the sold-out / relocated status (encoded as a CSS class on the listing card),
 * the subtitle line and the inferred event type. Merging is handled by
 * [BadehausWebsiteImporter].
 *
 * @see BadehausOverviewPageScraper for discovery + the authoritative fields.
 * @see BadehausWebsiteImporter for the HTTP fetch orchestrator.
 */
class BadehausDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the event
     * container or its title is missing (an unexpected page structure), so the
     * importer degrades to the overview data.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to
     *   derive the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing container/title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val event = document.selectFirst(".em-event-single") ?: return null
        val title =
            event
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }

        val metaText = event.text()

        return ScrapedEvent(
            title = title,
            description = parseDescription(event),
            // Detail pages carry the real date; sentinel when absent so the overview
            // value is used via BadehausWebsiteImporter.fillGapsFromOverview.
            eventDate = parseDate(event) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(EINLASS_PATTERN.find(metaText)?.groupValues?.get(1)),
            startTime = parseTime(BEGINN_PATTERN.find(metaText)?.groupValues?.get(1)),
            imageUrl = event.selectFirst(".single-event-image-wrap img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BADEHAUS.sourceIdPrefix}${extractSlug(sourceUrl)}",
            ticketUrl = event.selectFirst("a.ticketbtn")?.absUrl("href")?.takeIf { it.isNotBlank() },
            promoters = parsePromoters(event)
        )
    }

    /**
     * Joins the description paragraphs, dropping the meta paragraph (the one
     * carrying the "Einlass"/"Beginn" times and room), and social/blank lines.
     */
    private fun parseDescription(event: Element): String? =
        event
            .select(".em-event-single-content p, .em-event-single > p, .single-event-content p")
            .ifEmpty { event.select("p") }
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !EINLASS_PATTERN.containsMatchIn(it) && !BEGINN_PATTERN.containsMatchIn(it) }
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * Extracts promoter names from `a.promoterbtn` anchors (the icon markup is
     * dropped by `.text()`), deduplicated while preserving order.
     */
    private fun parsePromoters(event: Element): List<String> =
        event
            .select("a.promoterbtn")
            .mapNotNull { it.text().trim().takeIf { name -> name.isNotBlank() } }
            .distinct()

    /** Parses the `DD.MM.YYYY` date from the event header (e.g. "Fr. 04.12.2026 | 19:00 UHR"). */
    private fun parseDate(event: Element): LocalDate? {
        val header = event.selectFirst("h3")?.text().orEmpty()
        return parseGermanDate(DATE_PATTERN.find(header)?.value)
    }

    /** Extracts the event slug from a `/events/<slug>/` URL for a stable `sourceId`. */
    private fun extractSlug(url: String): String = URI(url).path.trim('/').substringAfterLast('/')

    private companion object {
        /** Matches a `DD.MM.YYYY` date. */
        private val DATE_PATTERN = Regex("""\d{2}\.\d{2}\.\d{4}""")

        /** Matches the doors time: "Einlass: 19:00" or "Einlass 19:00". */
        private val EINLASS_PATTERN = Regex("""Einlass:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /** Matches the start time: "Beginn: 20:00" or "Beginn 20:00". */
        private val BEGINN_PATTERN = Regex("""Beginn:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
    }
}
