package de.norm.events.scraper.aeden

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for a single ÆDEN `/month/?month=YYYY-MM` page.
 *
 * Every night is a `.single-accordion` block holding the full event data:
 * - `.event-title` — the party/series name (never a performer, see below);
 * - `.event-time` — the start time as `HH:mm`;
 * - `.event-date` — `"01/08/2026 Saturday"` (`d/M/yyyy` plus an English weekday, redundant and
 * ignored — the four-digit year needs no weekday inference);
 * - `.event-genre` — a comma-separated style list (`"Trance, Techno"`), stored raw as the genre;
 * - `.event-lineup` — a prose block stored as the description, whose `Lineup:` paragraph lists
 * the DJs one per `<br>` line (see [parseLineup]);
 * - `.event-poster img` — the poster; and
 * - `.accordion-footer a` — the external ticket link (Resident Advisor or Weeztix).
 *
 * Every event is [EventType.PARTY]: a techno club whose nights are DJ parties, so the title is a
 * series name (`silikon`, `TRINITY pt. IV`) rather than a headliner and never an artist. The
 * month page carries no per-event link and no prices, so `sourceId` is date plus slugified title.
 *
 * @see AedenWebsiteImporter for the fetch orchestration (entry page → month pages).
 */
class AedenOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from one month page.
     *
     * @param baseUrl the URL the document was fetched from; the `sourceUrl`, since the month page
     * links no detail pages.
     * @return one [ScrapedEvent] per night with a parseable date and title. Passed nights stay on
     * the current-month page and are dropped centrally at persistence (`EventUpsertService`), so
     * every dated night is returned as-is.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.single-accordion")
        logger.info { "Found ${items.size} event block(s) on ÆDEN month page $baseUrl" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed nights without aborting the whole import.
        return items.mapNotNull { item ->
            try {
                parseEvent(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse ÆDEN event block, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required date and title are clearer than nesting.
    private fun parseEvent(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val rawDate = item.textAt(".event-date")
        val eventDate =
            parseAedenDate(rawDate) ?: run {
                logger.warn { "Could not parse ÆDEN date '$rawDate', skipping event" }
                return null
            }
        val title =
            item.textAt(".event-title") ?: run {
                logger.warn { "No title in ÆDEN event block on $eventDate, skipping event" }
                return null
            }

        return ScrapedEvent(
            title = title,
            description = item.textAt(".event-lineup"),
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseTime(item.textAt(".event-time")),
            imageUrl = item.imgSrcAt(".event-poster img"),
            sourceUrl = baseUrl,
            sourceId = "${EventSource.AEDEN.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = item.hrefAt(".accordion-footer a"),
            genre = item.textAt(".event-genre"),
            artists = parseLineup(item)
        )
    }

    /**
     * The DJs announced in the lineup block, in billing order. The block is prose, but the roster
     * is consistently one paragraph opening with a `Lineup:` label followed by `<br>`-separated
     * names, so only that paragraph is read — the blurb paragraphs go to the description. Names
     * are cleaned of the decorative leading `&`/`+` on a final act, and announcement placeholders
     * (`TBA soon..`, `More TBA soon…`, `SECRET ACT`) are dropped rather than minted.
     */
    private fun parseLineup(item: Element): List<ScrapedArtist> {
        val lineupParagraph =
            item.select(".event-lineup p").firstOrNull { paragraph ->
                LINEUP_LABEL.containsMatchIn(paragraph.textLines().firstOrNull().orEmpty())
            } ?: return emptyList()

        return lineupParagraph
            .textLines()
            .map { it.replaceFirst(LINEUP_LABEL, "").replaceFirst(LEADING_CONJUNCTION, "").trim() }
            .filter { it.isNotBlank() && !isAnnouncementPlaceholder(it) && !isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "DJ") }
    }

    /**
     * Whether [line] is an "act still to be announced" note rather than a name. Complements the
     * shared [isNonArtistName] denylist, which matches only the bare `TBA`/`N.N.` tokens — ÆDEN
     * writes the announcement as a sentence (`TBA soon..`, `More TBA soon…`) and books unnamed
     * slots as `SECRET ACT`.
     */
    private fun isAnnouncementPlaceholder(line: String): Boolean = ANNOUNCEMENT_PLACEHOLDER.containsMatchIn(line)

    /** Parses the `"01/08/2026 Saturday"` date cell, ignoring the trailing weekday. Null when absent or unparseable. */
    private fun parseAedenDate(text: String?): LocalDate? {
        val datePart = text?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: return null
        return try {
            LocalDate.parse(datePart, AEDEN_DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        /** European numeric date (`d/M/yyyy`, e.g. `01/08/2026`) leading the `.event-date` cell. */
        private val AEDEN_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")

        /** The `Lineup:` label opening the roster paragraph, stripped off the first name. */
        private val LINEUP_LABEL = Regex("""^\s*line\s*-?\s*up\s*:?\s*""", RegexOption.IGNORE_CASE)

        /** A decorative `&`/`+` the venue prefixes to the last act of a roster (`& SECRET ACT`). */
        private val LEADING_CONJUNCTION = Regex("""^\s*[&+]\s*""")

        /** An announcement note standing in for an act: `TBA soon..`, `More TBA soon…`, `SECRET ACT`. */
        private val ANNOUNCEMENT_PLACEHOLDER =
            Regex("""\b(?:t\.?b\.?[adc]\.?|secret\s+act|more\s+\w+\s+soon)\b""", RegexOption.IGNORE_CASE)
    }
}
