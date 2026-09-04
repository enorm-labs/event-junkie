package de.norm.events.scraper.duncker

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for Duncker Club Berlin's retro `start.html` programme page.
 *
 * The site is hand-coded HTML with a single `<table class="bodytable">`: each
 * programme row is a `<tr>` of four cells — weekday, date, event, and time. The
 * event cell packs everything inline: the night's name in `<span class="eventname">`,
 * a free-text genre/style line, the flyer `<img>`, a Facebook-event link, and the
 * resident DJ(s). A few leading rows are pure flyer banners (no date, no
 * `eventname`) and are skipped.
 *
 * Every night is a DJ dance party, so all events are typed [EventType.PARTY].
 * Dates render as German `DD.MM.` with **no year**, but the row carries a German
 * two-letter weekday (Mo–So); the year is inferred from it via [inferYearForWeekday].
 * The venue leaves recently-passed nights on the page; those past-dated events are
 * dropped centrally at persistence time (`EventUpsertService`), so this parser returns
 * every dated row as-is.
 *
 * @see DunckerWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.dunckerclub.de/start.html">Duncker Club programme</a>
 */
@Suppress("TooManyFunctions") // Cohesive single-responsibility parser; the inline markup needs several small field extractors.
class DunckerOverviewPageScraper(
    /** Clock for weekday-based year inference. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the
     *   relative flyer image path and as each event's `sourceUrl`.
     * @return a list of [ScrapedEvent] instances, one per dated table row.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cells = document.select("table.bodytable td.tableevent")
        logger.info { "Found ${cells.size} programme row(s) on Duncker Club page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the whole import.
        val parsed =
            cells.mapNotNull { cell ->
                try {
                    parseRow(cell, baseUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Duncker event row, skipping" }
                    null
                }
            }

        return parsed
    }

    /** Parses one programme row (its event cell plus sibling date/weekday/time cells) into a [ScrapedEvent]. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting.
    private fun parseRow(
        cell: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val row = cell.parent() ?: return null

        // Banner rows (flyer images) carry no event name — skip them.
        val title = parseTitle(cell) ?: return null

        val eventDate =
            parseDate(row) ?: run {
                logger.warn { "Could not parse date for Duncker event '$title', skipping" }
                return null
            }

        val fbEventUrl = cell.select("a[href]").map { it.attr("href") }.firstOrNull { it.contains(FB_EVENT_PATH) }
        val fbEventId = fbEventUrl?.let { FB_EVENT_ID_PATTERN.find(it)?.groupValues?.get(1) }

        return ScrapedEvent(
            title = title,
            // The free-text style line ("Rock, Indie, Alternative, Punk") is display prose, not a
            // normalizable genre — keep it as a subtitle so it never seeds bogus genre tags.
            subtitle = parseSubtitle(cell),
            // Every listing is a resident DJ dance night.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            doorsTime = parseDoorsTime(row),
            imageUrl = parseImageUrl(cell, baseUrl),
            // No per-event pages on this single-page site — the programme page is the source.
            sourceUrl = baseUrl,
            // The Facebook event id is the stable canonical identity; fall back to date + title when absent.
            sourceId =
                fbEventId
                    ?.let { "${EventSource.DUNCKER.sourceIdPrefix}$it" }
                    ?: "${EventSource.DUNCKER.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = fbEventUrl,
            artists = parseDjs(cell),
            promoters = parsePromoters(cell)
        )
    }

    /**
     * The event title from `span.eventname`, with nested links removed.
     *
     * The name may span a `<br>` (kept, e.g. "Time Machine and The Cure Special")
     * but can also embed a presenter link (`<a class="eventLINK">TIQ</a>`) that is
     * a promoter, not part of the title — those anchors are dropped here and picked
     * up by [parsePromoters].
     */
    private fun parseTitle(cell: Element): String? {
        val nameEl = cell.selectFirst("span.eventname") ?: return null
        val cleaned = nameEl.clone().also { it.select("a").remove() }
        return cleaned.text().trim().takeIf { it.isNotBlank() }
    }

    /**
     * The free-text style/genre line: everything in the event cell that is not the
     * name span, a link, or the flyer image.
     */
    private fun parseSubtitle(cell: Element): String? =
        cell
            .clone()
            .also { it.select("span.eventname, a, img").remove() }
            .text()
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * Parses the row's German `DD.MM.` date, inferring the year from the two-letter
     * weekday cell (Mo–So) via [inferYearForWeekday]. Returns `null` when the date
     * cannot be parsed.
     */
    @Suppress("ReturnCount") // Null-safe early exits per date component are clearer than nested let-chains.
    private fun parseDate(row: Element): LocalDate? {
        val dateText = row.textAt("td.tabledate") ?: return null
        val match = DATE_PATTERN.find(dateText) ?: return null
        val (day, month) = match.destructured
        val monthDay =
            try {
                MonthDay.of(month.toInt(), day.toInt())
            } catch (_: DateTimeException) {
                return null
            }
        val weekday = parseGermanWeekdayAbbreviation(row.textAt("td.tableweekday"))
        return inferYearForWeekday(monthDay, weekday, clock)
    }

    /** Doors time from the "21h-05h" range cell — the opening hour, on the hour. */
    private fun parseDoorsTime(row: Element): LocalTime? {
        val hour =
            TIME_PATTERN
                .find(row.textAt("td.tabletime") ?: return null)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return hour?.let { runCatching { LocalTime.of(it, 0) }.getOrNull() }
    }

    /** The flyer image, resolved to an absolute URL against [baseUrl]. */
    private fun parseImageUrl(
        cell: Element,
        baseUrl: String
    ): String? =
        cell
            .selectFirst("img.listenLINK_img")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(baseUrl, it) }

    /**
     * Resident DJ(s) for the night, role [DJ][ScrapedArtist].
     *
     * DJ names appear either as the text of a link (a profile, or inside the
     * Facebook-event link) or as a trailing text node, always prefixed with the
     * "DJ"/"Djs" label. The label is stripped and multi-DJ lines
     * ("Djs Neue K & Lichene") are split into individual acts.
     */
    private fun parseDjs(cell: Element): List<ScrapedArtist> {
        val candidates = cell.select("a").map { it.text() } + cell.textNodes().map { it.text() }
        return candidates
            .map { it.trim() }
            .mapNotNull { DJ_PATTERN.find(it)?.groupValues?.get(1) }
            .flatMap { splitSupportActs(it) }
            .filterNot { isNonArtistName(it) }
            .distinct()
            .map { ScrapedArtist(name = it, role = "DJ") }
    }

    /** Presenter collective(s) linked inside the event name (`a.eventLINK`, e.g. "TIQ"). */
    private fun parsePromoters(cell: Element): List<String> =
        cell
            .select("a.eventLINK")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

    companion object {
        /** Path fragment identifying a Facebook event link (vs. a DJ/venue profile link). */
        private const val FB_EVENT_PATH = "facebook.com/events/"

        /** Captures the numeric event id from a Facebook event URL. */
        private val FB_EVENT_ID_PATTERN = Regex("""facebook\.com/events/(\d+)""")

        /** Captures the day and month from a "DD.MM." date cell. */
        private val DATE_PATTERN = Regex("""(\d{1,2})\.(\d{1,2})\.""")

        /** Captures the opening hour from a "21h-05h" time range. */
        private val TIME_PATTERN = Regex("""(\d{1,2})h""")

        /** A "DJ"/"Djs" label followed by the act name(s), captured to end of line. */
        private val DJ_PATTERN = Regex("""^djs?\b\.?\s+(.+)$""", RegexOption.IGNORE_CASE)
    }
}
