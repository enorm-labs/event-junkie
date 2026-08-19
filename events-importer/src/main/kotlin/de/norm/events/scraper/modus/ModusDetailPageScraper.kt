package de.norm.events.scraper.modus

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure HTML parser for Modus Berlin event detail pages (`/event/DDMMYY-<Name>`).
 *
 * Each page renders an `.event-view-right` column: an `h1` title, an `h2` restating the date
 * with the start time (`"24.09.2026 - 20:00"`), an `.all-events-button` ticket link (Eventim,
 * the venue's own shop, or the promoter's), and an `.event-description` prose block. The poster
 * is the `img.event-view-image` beside it.
 *
 * As on the listing, the date comes from the rendered `h2` rather than the slug, which keeps a
 * postponed show's original date (see [ModusOverviewPageScraper]). The **doors time has no
 * markup of its own** — where the venue publishes one at all it is a line inside the
 * description prose (`"Doors: 19:30"`, `"Beginn 20:00"`), so it is read from there.
 *
 * @see ModusOverviewPageScraper for the listing parser (discovery, date, poster).
 * @see ModusWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://modus-berlin.de/event/240926-c4rl">Example detail page</a>
 */
class ModusDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // A guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val rawTitle = document.textAt(".event-view-right h1")
        if (rawTitle == null) {
            logger.warn { "Modus detail page at $sourceUrl has no title, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/event/")
        val title = cleanEventTitle(rawTitle)
        val eventType = inferConcertVenueType(title)
        val dateLine = document.textAt(".event-view-right h2")
        val description = document.textAt(".event-description")

        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType,
            eventDate = parseGermanDate(dateLine?.substringBefore(DATE_TIME_SEPARATOR)) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseDoorsFromDescription(description),
            startTime = parseTime(dateLine?.substringAfter(DATE_TIME_SEPARATOR, "")?.trim()),
            imageUrl = document.imgSrcAt("img.event-view-image"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MODUS.sourceIdPrefix}$slug",
            ticketUrl = document.hrefAt(".all-events-button a"),
            status = parseEventStatus(rawTitle),
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /**
     * Reads the doors time out of the description prose, the only place the venue ever states
     * one. Both spellings it uses are accepted — the English `"Doors: 19:30"` and the German
     * `"Einlass 19:30"` — while a `"Start"` / `"Beginn"` line is ignored here because the `h2`
     * already carries the start time in markup. Returns `null` when the prose names no doors
     * time, which is the common case.
     */
    private fun parseDoorsFromDescription(description: String?): LocalTime? {
        val match = DOORS_PATTERN.find(description.orEmpty()) ?: return null
        return parseTime(match.groupValues[1])
    }
}

/** Matches the doors line venues write into Modus's description prose (`"Doors: 19:30"`, `"Einlass 19:30"`). */
private val DOORS_PATTERN = Regex("""(?:doors|einlass)\s*:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

/** The separator the detail `h2` puts between the date and the start time (`"24.09.2026 - 20:00"`). */
private const val DATE_TIME_SEPARATOR = " - "
