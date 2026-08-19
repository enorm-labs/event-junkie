package de.norm.events.scraper.monarch

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Monarch Berlin's retro `programm.php` event page.
 *
 * The site is hand-coded PHP with **no semantic structure**: the whole programme
 * lives on one page as a flat run of presentational `<div>` blocks, one per event,
 * and there are no per-event URLs. Each block carries:
 * - a leading bold date line — `Weekday DD/MM/YYYY-HH:MM` (e.g. "Samstag 11/07/2026-18:30");
 * - a title cell `td#td1`, where a trailing `(KONZERT)` suffix marks a concert and a
 *   leading `ABGESAGT` marks a cancellation; and
 * - an optional external "Ticket Vorverkauf" shop link (eventim, dice, ra.co, …).
 *
 * Parsing anchors on the one stable, semantic-ish handle in the markup: the title
 * cell's `id=td1`. The enclosing event block is its nearest `<div>` ancestor, which
 * supplies the date line and ticket link. The date carries a full four-digit year,
 * so no weekday-based year inference is needed. The venue leaves stale past events
 * listed; those are dropped centrally at persistence time (`EventUpsertService`), so
 * this parser returns every dated block as-is.
 *
 * The block's secondary `td.tom` cell mixes real support lineups with pay-at-door
 * notes ("Abendkasse") and free-text blurbs with no structural separator, so it is
 * intentionally not parsed for artists — only a concert's title-derived headliner is
 * extracted, avoiding minting notes/descriptions as artist entries.
 *
 * @see MonarchWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://kottimonarch.de/programm.php">Monarch programme</a>
 */
class MonarchOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Monarch programme page document.
     *
     * @param baseUrl the URL the document was fetched from, used as each event's `sourceUrl`.
     * @return a list of [ScrapedEvent] instances, one per event block.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val titleCells = document.select("td#td1")
        logger.info { "Found ${titleCells.size} event block(s) on Monarch programme" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed blocks without aborting the whole import
        return titleCells.mapNotNull { cell ->
            try {
                parseEvent(cell, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Monarch event, skipping" }
                null
            }
        }
    }

    /** Parses one event block (anchored on its `td#td1` title cell) into a [ScrapedEvent]. */
    @Suppress("ReturnCount") // Guard clauses for the required block/date/title are clearer than nesting
    private fun parseEvent(
        titleCell: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val block = titleCell.closest("div") ?: return null

        // The date line is the block's leading bold text ("Samstag 11/07/2026-18:30").
        val dateText = block.selectFirst("b")?.text().orEmpty()
        val eventDate = parseDate(dateText)
        if (eventDate == null) {
            logger.warn { "Could not parse Monarch date from '$dateText', skipping" }
            return null
        }

        val rawTitle = titleCell.text().trim()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) {
            logger.warn { "Monarch event on $eventDate has no title, skipping" }
            return null
        }

        // "(KONZERT)" is the venue's authoritative concert marker. For everything else
        // (DJ nights, parties, other formats) the title is classified by keyword —
        // a party/quiz/show/… cue types it, otherwise it stays OTHER. It deliberately
        // does not default unmarked events to CONCERT (see [inferUnmarkedTitleType]).
        val eventType =
            if (KONZERT_PATTERN.containsMatchIn(rawTitle)) mapEventType("konzert") else inferUnmarkedTitleType(rawTitle)
        // "ABGESAGT" in the raw title flags a cancellation.
        val status = parseEventStatus(rawTitle)

        // The ticket link is the external "Ticket Vorverkauf" anchor; artist info links
        // inside td.tom carry only an icon (no text), so they never match here.
        val ticketUrl = block.hrefAt("a:contains(Vorverkauf)")

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = eventDate,
            startTime = parseStartTime(dateText),
            // No per-event URLs on this single-page site — the programme page is the source.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.MONARCH.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = ticketUrl,
            status = status,
            // Only a concert's title names the headliner; parties/club nights extract none.
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /** Parses the calendar date from a "Weekday DD/MM/YYYY-HH:MM" line, or `null` when absent/invalid. */
    @Suppress("ReturnCount") // Early exits per date component are clearer than nested lets
    private fun parseDate(dateText: String): LocalDate? {
        val match = DATE_TIME_PATTERN.find(dateText) ?: return null
        val (day, month, year) = match.destructured
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Parses the start time from a "Weekday DD/MM/YYYY-HH:MM" line, or `null` when absent/invalid. */
    private fun parseStartTime(dateText: String): LocalTime? {
        val match = DATE_TIME_PATTERN.find(dateText) ?: return null
        val hour = match.groupValues[HOUR_GROUP].toInt()
        val minute = match.groupValues[MINUTE_GROUP].toInt()
        return try {
            LocalTime.of(hour, minute)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Strips the "(KONZERT)" type marker and "ABGESAGT" cancellation flag from the raw title cell text. */
    private fun cleanTitle(rawTitle: String): String =
        rawTitle
            .replace(KONZERT_PATTERN, " ")
            .replace(ABGESAGT_PATTERN, " ")
            .replace(WHITESPACE, " ")
            .trim()

    companion object {
        /**
         * Matches the block's date line "<Weekday> DD/MM/YYYY-HH:MM", capturing day,
         * month, year, hour and minute. The weekday prefix is redundant (the year is
         * explicit) and ignored.
         */
        private val DATE_TIME_PATTERN = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})-(\d{1,2}):(\d{2})""")
        private const val HOUR_GROUP = 4
        private const val MINUTE_GROUP = 5

        /** The "(KONZERT)" concert marker suffix, case-insensitive. */
        private val KONZERT_PATTERN = Regex("""\(\s*konzert\s*\)""", RegexOption.IGNORE_CASE)

        /** The "ABGESAGT" (cancelled) flag, matched as a whole word anywhere in the title. */
        private val ABGESAGT_PATTERN = Regex("""\babgesagt\b""", RegexOption.IGNORE_CASE)

        /** Collapses runs of whitespace left after stripping the type/status markers. */
        private val WHITESPACE = Regex("""\s+""")
    }
}
