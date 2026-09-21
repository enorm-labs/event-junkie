package de.norm.events.scraper.badehaus

import de.norm.events.event.EventStatus
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.parseTitleStatus
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate

/**
 * Pure HTML parser for Badehaus Berlin event detail (`/events/<slug>/`) pages.
 *
 * The **primary source for the richer fields the card omits**: the full description, the start
 * time (`Beginn`, or `Start` on the pages that label in English — "Doors: 19:00h | Start
 * 20:00h") and the promoter (`a.promoterbtn`). It reuses the theme's `.em-event-single`
 * container and also carries title, date, doors time, image and ticket link as fallbacks.
 *
 * The overview stays authoritative for what only it exposes reliably — the sold-out flag (a
 * CSS class on the card), the subtitle and the inferred type. The card's `VERLEGT` class is one
 * flag for two changes, a date move and a house move; the notice the page opens with ("wurde
 * auf den 27.02.2027 verschoben", "vom Badehaus ins Mikropol verlegt") says which, so its first
 * sentence is read as the [status][ScrapedEvent.status] and kept as the
 * [statusNote][ScrapedEvent.statusNote] (#1578). [BadehausWebsiteImporter] merges.
 *
 * @see BadehausOverviewPageScraper for discovery + the authoritative fields.
 * @see BadehausWebsiteImporter for the HTTP fetch orchestrator.
 */
class BadehausDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` when the event container or its title
     * is missing, so the importer degrades to the overview data.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing container/title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val event = document.selectFirst(".em-event-single") ?: return null
        val title = event.textAt("h1")
        if (title.isNullOrBlank()) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }

        val metaText = event.text()
        val description = parseDescription(event)
        // Detail pages carry the real date; sentinel when absent so the overview value is used via
        // BadehausWebsiteImporter.fillGapsFromOverview.
        val eventDate = parseDate(event) ?: UNRESOLVED_EVENT_DATE
        val notice = description?.let(::parseNotice)
        val status = notice?.let { noticeStatus(it, eventDate) } ?: EventStatus.SCHEDULED.name

        return ScrapedEvent(
            title = title,
            description = description,
            status = status,
            statusNote = notice.takeIf { status != EventStatus.SCHEDULED.name },
            eventDate = eventDate,
            doorsTime = parseTime(EINLASS_PATTERN.find(metaText)?.groupValues?.get(1)),
            startTime = parseTime(BEGINN_PATTERN.find(metaText)?.groupValues?.get(1)),
            imageUrl = event.selectFirst(".single-event-image-wrap img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BADEHAUS.sourceIdPrefix}${badehausEventSlug(sourceUrl)}",
            ticketUrl = event.selectFirst("a.ticketbtn")?.absUrl("href")?.takeIf { it.isNotBlank() },
            promoters = parsePromoters(event)
        )
    }

    /**
     * Joins the description paragraphs, dropping the meta paragraph (the "Einlass"/"Beginn" times
     * and room) and social/blank lines.
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
     * The opening sentence of the description when it announces a status change, else `null`.
     * Only the first sentence: a blurb recalling an older postponement further down must not flip
     * a scheduled show. The whole sentence goes to [parseEventStatus], which reads "auf den <date>
     * verlegt" as a date move.
     */
    private fun parseNotice(description: String): String? =
        description
            .lineSequence()
            .first()
            .split(SENTENCE_END)
            .first()
            .trim()
            .takeIf { parseTitleStatus(it) != null }

    /**
     * The status a [notice] announces for the row dated [eventDate]. The notice stays on the page
     * after the move ("vom 25.02.26 auf den 22.09.26 verschoben" on the 22.09. row), so a
     * postponement whose target is this row's own date is history, not a change.
     */
    private fun noticeStatus(
        notice: String,
        eventDate: LocalDate
    ): String {
        val status = parseEventStatus(notice)
        val target =
            NOTICE_TARGET_DATE
                .find(notice)
                ?.groupValues
                ?.get(1)
                ?.let { parseGermanDate(it) ?: parseGermanShortDate(it) }
        return if (status == EventStatus.POSTPONED.name && target == eventDate) EventStatus.SCHEDULED.name else status
    }

    /**
     * Promoter names from `a.promoterbtn` anchors (icon markup dropped by `.text()`), deduplicated in order.
     */
    private fun parsePromoters(event: Element): List<String> =
        event
            .select("a.promoterbtn")
            .mapNotNull { it.text().trim().takeIf { name -> name.isNotBlank() } }
            .distinct()

    /** The `DD.MM.YYYY` date from the event header (e.g. "Fr. 04.12.2026 | 19:00 UHR"). */
    private fun parseDate(event: Element): LocalDate? {
        val header = event.selectFirst("h3")?.text().orEmpty()
        return parseGermanDate(DATE_PATTERN.find(header)?.value)
    }

    private companion object {
        /** A `DD.MM.YYYY` date. */
        private val DATE_PATTERN = Regex("""\d{2}\.\d{2}\.\d{4}""")

        /** The doors time: "Einlass: 19:00", "Einlass 19:00" or, on the pages that label in English, "Doors: 19:00h" (#1497). */
        private val EINLASS_PATTERN = Regex("""(?:Einlass|Doors):?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /** The start time: "Beginn: 20:00", "Beginn 20:00" or "Start 20:00h". */
        private val BEGINN_PATTERN = Regex("""(?:Beginn|Start):?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /** The date a notice moves the show to: "auf den 27.02.2027" or "auf den 22.09.26". */
        private val NOTICE_TARGET_DATE = Regex("""auf\s+den\s+(\d{1,2}\.\d{1,2}\.\d{2,4})""", RegexOption.IGNORE_CASE)

        /** A sentence boundary; a date's dots are followed by digits, so "27.02.2027" survives. */
        private val SENTENCE_END = Regex("""(?<=[.!?;])\s+""")
    }
}
