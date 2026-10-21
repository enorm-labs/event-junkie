package de.norm.events.scraper.modus

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.labelledClock
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
 * One `.event-view-right` column: an `h1` title, an `h2` restating the date with the start time
 * (`"24.09.2026 - 20:00"`), an `.all-events-button` ticket link (Eventim, the venue's own shop,
 * or the promoter's), and an `.event-description` prose block. The poster is the
 * `img.event-view-image` beside it.
 *
 * As on the listing, the date comes from the rendered `h2`, not the slug, which keeps a
 * postponed show's original date (see [ModusOverviewPageScraper]). The **doors time has no
 * markup of its own** — where published at all it is a line inside the description prose
 * (`"Doors: 19:30"`, `"Beginn 20:00"`), so it is read from there.
 *
 * @see ModusOverviewPageScraper for the listing parser (discovery, date, poster).
 * @see ModusWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://modus-berlin.de/event/240926-c4rl">Example detail page</a>
 */
class ModusDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without a title.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val rawTitle = document.textAt(".event-view-right h1")
        if (rawTitle == null) {
            logger.warn { "Modus detail page has no title, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/event/")
        val title = cleanEventTitle(rawTitle)
        val eventType = modusEventType(title)
        val dateLine = document.textAt(".event-view-right h2")
        val description = document.textAt(".event-description")?.replace(INVISIBLE_FORMAT_CHARS, "")

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
            statusNote = rawTitle,
            artists = modusLineup(title, eventType, document.selectFirst(".event-description"))
        )
    }

    /**
     * The doors time out of the description prose, the only place the venue states one. Both
     * spellings are accepted — the English `"Doors: 19:30"` and the German `"Einlass 19:30"` —
     * while a `"Start"` / `"Beginn"` line is ignored because the `h2` already carries the start in
     * markup. A sentence that puts words between the label and the clock is read too:
     * `"Der Einlass zu den Konzerten beginnt um 19:30 Uhr"`. `null` when the prose names no doors
     * time, the common case.
     */
    private fun parseDoorsFromDescription(description: String?): LocalTime? =
        labelledClock(description, DOORS_LABELS)
            ?: description
                ?.let { DOORS_SENTENCE.find(it) }
                ?.destructured
                ?.let { (hour, minute) -> parseTime("$hour:$minute") }
}

/** The separator the detail `h2` puts between the date and the start time (`"24.09.2026 - 20:00"`). */
private const val DATE_TIME_SEPARATOR = " - "

/**
 * Invisible characters the venue's editor leaves in the prose: zero-width space, non-joiner and joiner,
 * word joiner, byte-order mark, soft hyphen. One inside the doors clock (`19:` U+200B `30`) hides it.
 */
private val INVISIBLE_FORMAT_CHARS = Regex("""[\u200B-\u200D\u2060\uFEFF\u00AD]""")

/** `Einlass`, a few words within the sentence, then `um` or `ab` and the clock. */
private val DOORS_SENTENCE = Regex("""(?<!\p{L})einlass(?!\p{L})[^.!?\n]{0,40}?\s(?:um|ab)\s+(\d{1,2})[:.](\d{2})(?!\d)""", RegexOption.IGNORE_CASE)
