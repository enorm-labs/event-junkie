package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.B2B_SEPARATOR
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
 * Both source pages share one template: the main `/de/program/` page (Berghain building
 * floors) and `/de/program/kantine-am-berghain/` (concert hall). Each night is a
 * self-contained `a[href^=/de/event/<id>/]` block, so events are discovered by that semantic
 * link selector rather than the per-page wrapper class (`upcoming-event` on the main page,
 * plain `block` on Kantine). Within a block:
 * - a leading `<p>` carries the weekday, a `span.font-bold` German `DD.MM.YYYY` date, and
 * inline `tür` (doors) / `beginn` (start) times;
 * - `<h2>` is the event title;
 * - one or more `<h3>` labels name the floor(s) (Berghain, Panorama Bar, Säule, Halle, or
 * Kantine am Berghain) — used to type the event and as the subtitle;
 * - one or more `<h4>` blocks hold the running-order lineup, each act in its own leaf `<span>`,
 * with `Live` / `b2b` format markers in `uppercase` spans — `Live` bills its act a headliner,
 * `b2b` joins two DJ sets and leaves them DJs.
 *
 * The listing carries only upcoming events, but recently-passed dates are dropped here
 * (mirroring the persistence cutoff) to avoid wasted detail-page fetches.
 *
 * @see BerghainDetailPageScraper for the per-event enrichment source (image, prices, ticket, description).
 */
class BerghainOverviewPageScraper(
    /** Clock for the past-event cutoff; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event block on the overview page.
     *
     * @param sourceUrl the URL the document was fetched from, for resolving relative event links.
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
        val eventType = floorsToEventType(floors)

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = parseTime(BERGHAIN_DOORS_PATTERN.find(lineText)?.groupValues?.get(1)),
            startTime = parseTime(BERGHAIN_START_PATTERN.find(lineText)?.groupValues?.get(1)),
            sourceUrl = resolveUrl(sourceUrl, href),
            sourceId = "${EventSource.BERGHAIN.sourceIdPrefix}$eventId",
            genre = floorsToGenre(floors),
            artists = parseLineup(block, eventType)
        )
    }

    /**
     * The running-order lineup, each act tagged with its floor. The block interleaves `<h3>` floor
     * headings with that floor's `<h4>` lineup, so a running scan pairs each act with the most
     * recent floor as its [stage][ScrapedArtist.stage]. Kantine lineups are headliners; a club
     * floor is DJ sets unless the venue says otherwise.
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
            } else {
                lineup += parseFloorLineup(element, role, currentStage)
            }
        }
        return lineup
    }

    /**
     * One floor's `<h4>` into acts. Each act is a leaf `<span>`.
     *
     * **A `Live` marker is the venue saying the act performs** (#1787), so that act is billed
     * [HEADLINER][de.norm.events.event.ArtistRole.HEADLINER] rather than
     * [DJ][de.norm.events.event.ArtistRole.DJ] — the reading Klunkerkranich, OHM and Club der
     * Visionäre already give their own live markers, HEADLINER being the only performing role the
     * model has. The marker sits inside the act's wrapper span, after the name, so document order
     * is the pairing, and it marks every act the preceding name span produced:
     *
     * ```html
     * <span class="font-bold">
     *   <span class="xs:whitespace-no-wrap">Krallice</span>
     *   <span class="… uppercase">Live</span>,
     * </span>
     * ```
     *
     * **The venue writes a back-to-back slot two ways** (#1759). Sometimes the join is its own
     * `uppercase` span, which leaves a leaf span per DJ — and is why only `Live` promotes, a `b2b`
     * marker joining two DJ sets. Sometimes it is plain text inside one name span, `"Agata B2B Cunt
     * Remember"`, which [B2B_SEPARATOR] splits. Back-to-back is never one act, which is why that
     * split is safe where a conjunction would not be: `"Blasha & Allatt"` is a duo in the same shape.
     *
     * **A comma inside a name span is the same shape one separator further on** (#1789). The venue
     * wrote the five DJs of one wsnwg night into a single span, and the span was stored as one
     * artist. A comma is safe here where it is not in an event title: this span is a lineup slot,
     * so its content is a list of performers, and a band whose name carries a comma is billed in a
     * span of its own.
     */
    private fun parseFloorLineup(
        element: Element,
        role: String,
        stage: String?
    ): List<ScrapedArtist> {
        val acts = mutableListOf<ScrapedArtist>()
        // The acts the last name span produced, so a marker that follows can bill them live. An
        // empty range is a marker with no act before it, which marks nothing rather than failing.
        var marked = IntRange.EMPTY
        for (span in element.select("span").filter { it.children().isEmpty() }) {
            val text = span.text().trim()
            if (span.hasClass(MARKER_CLASS)) {
                if (text.equals(LIVE_MARKER, ignoreCase = true)) {
                    for (i in marked) acts[i] = acts[i].copy(role = "HEADLINER")
                }
                continue
            }
            val first = acts.size
            text
                .split(SLOT_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotBlank() && !isNonArtistName(it) }
                .forEach { acts.add(ScrapedArtist(name = it, role = role, stage = stage)) }
            marked = first until acts.size
        }
        return acts
    }

    companion object {
        /** Semantic selector for event blocks — a link to a `/de/event/<id>/` page, on either source page. */
        private const val EVENT_LINK_SELECTOR = "a[href^=/de/event/]"

        /** Tailwind utility class marking a `Live`/`b2b` format label span (not an artist name). */
        private const val MARKER_CLASS = "uppercase"

        /** The one marker that changes a role: the venue's statement that the act plays live. */
        private const val LIVE_MARKER = "Live"

        /**
         * What separates the performers written into one name span: a [B2B_SEPARATOR], a padded
         * `+` (Kantine's `Dicken45 + Benito`, #1844), or a comma. The comma needs no padding,
         * because the venue writes the list both ways.
         */
        private val SLOT_SEPARATOR = Regex("""${B2B_SEPARATOR.pattern}|\s+\+\s+|\s*,\s*""", RegexOption.IGNORE_CASE)
    }
}
