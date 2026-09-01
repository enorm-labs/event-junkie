package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock

/**
 * Pure HTML parser for Berghain's server-rendered programme (overview) page.
 *
 * Handles both source pages, which share one template: the main `/de/program/`
 * page (Berghain building floors) and the `/de/program/kantine-am-berghain/`
 * concert-hall page. Each night is a self-contained `a[href^=/de/event/<id>/]`
 * block, so events are discovered by that semantic link selector rather than the
 * per-page wrapper class (`upcoming-event` on the main page, plain `block` on
 * Kantine). Within a block:
 * - a leading `<p>` carries the weekday, a `span.font-bold` German `DD.MM.YYYY`
 *   date, and inline `tür` (doors) / `beginn` (start) times;
 * - `<h2>` is the event title;
 * - one or more `<h3>` labels name the floor(s) (Berghain, Panorama Bar, Säule,
 *   Halle, or Kantine am Berghain) — used to type the event and as the subtitle;
 * - one or more `<h4>` blocks hold the running-order lineup, each act in its own
 *   leaf `<span>`, with `Live` / `b2b` format markers in `uppercase` spans.
 *
 * The listing carries only upcoming events, but recently-passed dates are dropped here (mirroring
 * the persistence cutoff) to avoid wasted detail-page fetches.
 *
 * @see BerghainDetailPageScraper for the per-event enrichment source (image, prices, ticket, description).
 */
class BerghainOverviewPageScraper(
    /** Clock for the past-event cutoff. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event block on the overview page document.
     *
     * @param sourceUrl the URL the document was fetched from, used to resolve
     *   relative event links.
     * @return upcoming [ScrapedEvent]s (today onward); past-dated entries are dropped.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val blocks = document.select(EVENT_LINK_SELECTOR)
        logger.info { "Found ${blocks.size} event block(s) on Berghain overview page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import
        val events =
            blocks.mapNotNull { block ->
                try {
                    parseBlock(block, sourceUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Berghain event block, skipping" }
                    null
                }
            }

        return events.dropPastEvents(clock) { dropped ->
            logger.info { "Dropped $dropped past event(s) from Berghain listing" }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for missing title/date are clearer than nesting
    private fun parseBlock(
        block: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val href = block.attr("href")
        val eventId = href.trim('/').substringAfterLast('/')
        if (eventId.isBlank()) return null

        val title =
            block.textAt("h2") ?: run {
                logger.warn { "Berghain event block $href has no title, skipping" }
                return null
            }

        val dateLine = block.selectFirst("p")
        val eventDate =
            parseGermanDate(dateLine?.selectFirst("span.font-bold")?.text()) ?: run {
                logger.warn { "Could not parse date for Berghain event '$title' ($href), skipping" }
                return null
            }

        val lineText = dateLine?.text().orEmpty()
        val floors = block.select("h3").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
        val eventType = floorToEventType(floors)

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = parseTime(DOORS_PATTERN.find(lineText)?.groupValues?.get(1)),
            startTime = parseTime(START_PATTERN.find(lineText)?.groupValues?.get(1)),
            sourceUrl = resolveUrl(sourceUrl, href),
            sourceId = "${EventSource.BERGHAIN.sourceIdPrefix}$eventId",
            genre = floorsToGenre(floors),
            artists = parseLineup(block, eventType)
        )
    }

    /**
     * Extracts the running-order lineup, tagging each act with the floor it plays.
     *
     * The block interleaves `<h3>` floor headings with the `<h4>` lineup for that
     * floor, so a running scan pairs each act with the most recent floor as its
     * [stage][ScrapedArtist.stage]. Each act is a leaf `<span>` (an inner name span);
     * the `Live` / `b2b` format markers live in `uppercase` spans and are skipped, so
     * a `"A b2b B"` back-to-back slot naturally yields two artists on the same floor.
     * Concert-hall (Kantine) lineups are billed as headliners; club floors are DJ sets.
     */
    private fun parseLineup(
        block: Element,
        eventType: String?
    ): List<ScrapedArtist> {
        val role = if (eventType == EventType.CONCERT.name) "HEADLINER" else "DJ"
        val lineup = mutableListOf<ScrapedArtist>()
        var currentStage: String? = null
        for (element in block.select("h3, h4")) {
            if (element.tagName() == "h3") {
                currentStage = element.text().trim().takeIf { it.isNotBlank() }
                continue
            }
            element
                .select("span")
                .filter { it.children().isEmpty() && !it.hasClass(MARKER_CLASS) }
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !isNonArtistName(it) }
                .forEach { lineup.add(ScrapedArtist(name = it, role = role, stage = currentStage)) }
        }
        return lineup
    }

    /**
     * Types an event from its floor label(s): the Kantine am Berghain concert hall
     * lists live [CONCERT][EventType.CONCERT]s, while the Berghain building floors
     * (Berghain, Panorama Bar, Säule, Halle) host club [PARTY][EventType.PARTY]
     * nights. Returns `null` when no floor is given, letting the persistence
     * boundary apply the `OTHER` default.
     */
    private fun floorToEventType(floors: List<String>): String? =
        when {
            floors.any { it.contains(KANTINE_MARKER, ignoreCase = true) } -> EventType.CONCERT.name
            floors.isNotEmpty() -> EventType.PARTY.name
            else -> null
        }

    companion object {
        /** Semantic selector for event blocks — a link to a `/de/event/<id>/` detail page, on either source page. */
        private const val EVENT_LINK_SELECTOR = "a[href^=/de/event/]"

        /** Tailwind utility class marking a `Live`/`b2b` format label span (not an artist name). */
        private const val MARKER_CLASS = "uppercase"

        /** Floor label identifying the adjacent concert hall (vs. the Berghain building's club floors). */
        private const val KANTINE_MARKER = "Kantine"

        /** Doors time in the date line, e.g. "tür 19:00" (German "Tür" = door). */
        private val DOORS_PATTERN = Regex("""tür\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /** Show start time in the date line, e.g. "beginn 21:00". */
        private val START_PATTERN = Regex("""beginn\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
    }
}
