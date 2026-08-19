package de.norm.events.scraper.schokoladen

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.schokoladen.SchokoladenOverviewPageScraper.Companion.DOORS_PATTERN
import de.norm.events.scraper.schokoladen.SchokoladenOverviewPageScraper.Companion.SHOW_PATTERN
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalTime

/**
 * Pure HTML parser for Schokoladen Mitte's Laravel-based event overview page.
 *
 * Schokoladen renders all upcoming events on a single page (`/`) with full
 * details expanded inline. Each event is a `div.event` block split across two
 * `div.container` children: a collapsible header (category, date, promoter,
 * title, subtitle) and an `div.event-info` body (times, ticket link,
 * description, image carousel). Individual events are addressed only by a page
 * fragment (`#e20260711`), not a separate URL — so no detail-page fetching is
 * needed and this is a single-page importer.
 *
 * The `div.event-info` carries a machine-readable `data-event-date` attribute
 * (ISO 8601, e.g. `2026-07-11`) and a matching `id` (`e<yyyymmdd>`). These are
 * the most stable extraction targets: the date needs no year inference, and the
 * fragment id yields a stable per-event `sourceId`.
 *
 * @see SchokoladenWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.schokoladen-mitte.de/">Schokoladen Mitte</a>
 */
class SchokoladenOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Schokoladen overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used for resolving relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val eventBlocks = document.select("div.event")
        logger.info { "Found ${eventBlocks.size} event block(s) on page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the whole import
        return eventBlocks.mapNotNull { block ->
            try {
                parseEvent(block, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Schokoladen event block, skipping" }
                null
            }
        }
    }

    /**
     * Parses a single `div.event` block into a [ScrapedEvent], or `null` when a
     * required field (title, date, or fragment id) is missing.
     */
    @Suppress("ReturnCount") // Null-safe early exits for each required field are clearer than nested let-chains
    private fun parseEvent(
        block: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = block.textAt("h2.fw-bold")
        if (title.isNullOrBlank()) {
            logger.warn { "Schokoladen event block has no title, skipping" }
            return null
        }

        val info = block.selectFirst(".event-info")
        val eventId = info?.id()?.takeIf { it.isNotBlank() }
        if (eventId == null) {
            logger.warn { "Event '$title' has no fragment id, skipping" }
            return null
        }

        val eventDate = info.attr("data-event-date").takeIf { it.isNotBlank() }?.let { parseIsoDate(it) }
        if (eventDate == null) {
            logger.warn { "Could not parse event date for '$title', skipping" }
            return null
        }

        // The site addresses each event by page fragment; that fragment is its canonical, stable identity.
        val sourceUrl = resolveUrl(baseUrl, "#$eventId")

        val eventType = mapEventType(block.textAt("h6.category"), CATEGORY_SYNONYMS)
        val subtitle = block.textAt("h6.subtitle")
        val (doorsTime, startTime) = parseTimes(info, block)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = parseDescription(info),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = parseImageUrl(info, baseUrl),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SCHOKOLADEN.sourceIdPrefix}$eventId",
            ticketUrl = block.hrefAt("a.ticket-btn"),
            artists = parseArtists(title, subtitle, eventType),
            promoters = parsePromoter(block)?.let { listOf(it) } ?: emptyList()
        )
    }

    /**
     * Parses the doors and show times from the event-facts "Time" line.
     *
     * The times sit in free text under a `<strong>Time</strong>` label and vary
     * in spelling: `"doors 19:00 - show 20:00"`, `"Doors 19h / Show 20h"`,
     * `"Einlass 19h Beginn 20h"`, `"Einlass: 18:30 Uhr"`. [DOORS_PATTERN] and
     * [SHOW_PATTERN] pick the two labelled times; the header's `span.d-none`
     * (`"19:00 Uhr"`) is a doors fallback when the line has no parseable time.
     */
    private fun parseTimes(
        info: Element?,
        block: Element
    ): Pair<LocalTime?, LocalTime?> {
        val timeText = info?.textAt(".event-facts p:has(strong:contains(Time)) span") ?: ""
        val doors = flexTime(DOORS_PATTERN.find(timeText)) ?: flexTime(HEADER_TIME_PATTERN.find(block.textAt("span.d-none") ?: ""))
        val start = flexTime(SHOW_PATTERN.find(timeText))
        return doors to start
    }

    /**
     * Extracts the event image from the media column.
     *
     * Events render either a Bootstrap carousel (the first `.carousel-item` is
     * `active`) or a single `.imageWrapper`; either way the first `<img>` in the
     * media column is the primary image. Sources are site-relative
     * (`/media/images/…`) and resolved against [baseUrl].
     */
    private fun parseImageUrl(
        info: Element?,
        baseUrl: String
    ): String? {
        val src = info?.attrAt(".col-md-9.offset-md-3 img", "src") ?: return null
        return resolveUrl(baseUrl, src)
    }

    /**
     * Joins the paragraphs of the `.event-description` block into a single
     * newline-separated description, or `null` when there is no description.
     */
    private fun parseDescription(info: Element?): String? {
        val description = info?.selectFirst(".event-description") ?: return null
        return description
            .select("p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the promoter name from the header's `span.promoter`, stripping a
     * trailing "presents:" / "prsnts:" flourish (e.g. `"little league shows
     * prsnts:"` → `"little league shows"`). Returns `null` when absent.
     */
    private fun parsePromoter(block: Element): String? =
        block
            .textAt("span.promoter")
            ?.replace(PRESENTS_SUFFIX, "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * Derives artist entries from the title (headliner) plus any support acts.
     *
     * Schokoladen embeds a "(genre, origin)" annotation after each act in the
     * title (`"MOLOCH (punk, bln) + PINK WONDER (scumpunk, bln)"`); those
     * parentheticals are stripped before the shared
     * [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType]
     * co-bill splitter runs, so headliners come out clean (`MOLOCH`, `PINK
     * WONDER`). The original title is still stored verbatim on the event — only
     * the derived artist names are cleaned. Non-concert events (readings,
     * specials) yield no artists unless a "Support:" line is present.
     */
    private fun parseArtists(
        title: String,
        subtitle: String?,
        eventType: String?
    ): List<ScrapedArtist> {
        val artistTitle = title.replace(GENRE_PARENTHETICAL, " ").replace(WHITESPACE, " ").trim()
        return buildArtistsForEventType(artistTitle, subtitle, eventType)
    }

    /** Builds a [LocalTime] from a doors/show regex match whose groups are (hour, optional minute), or `null`. */
    private fun flexTime(match: MatchResult?): LocalTime? {
        val hour = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    companion object {
        /** Venue category labels that the shared [mapEventType] table doesn't cover. "Musik" is live music → CONCERT. */
        private val CATEGORY_SYNONYMS = mapOf("musik" to "CONCERT")

        /** A "(genre, origin)" annotation appended to each act in a title, stripped before artist derivation. */
        private val GENRE_PARENTHETICAL = Regex("""\s*\([^)]*\)""")

        /** Collapses runs of whitespace left behind after stripping parentheticals. */
        private val WHITESPACE = Regex("""\s+""")

        /** A trailing "presents:" / "prsnts:" / "pres.:" promoter flourish. */
        private val PRESENTS_SUFFIX = Regex("""\s*(?:presents|prsnts|pres\.?)\s*:?\s*$""", RegexOption.IGNORE_CASE)

        /** Doors time after a "doors" / "Einlass" label — hour with optional `:mm`, tolerating a trailing `h`/`Uhr`. */
        private val DOORS_PATTERN = Regex("""(?:doors|einlass)\s*:?\s*(\d{1,2})(?:[:.](\d{2}))?""", RegexOption.IGNORE_CASE)

        /** Show time after a "show" / "Beginn" label. */
        private val SHOW_PATTERN = Regex("""(?:show|beginn)\s*:?\s*(\d{1,2})(?:[:.](\d{2}))?""", RegexOption.IGNORE_CASE)

        /** Header `span.d-none` time ("19:00 Uhr"), used as a doors fallback. */
        private val HEADER_TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")
    }
}
