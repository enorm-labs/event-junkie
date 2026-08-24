package de.norm.events.scraper.humboldthain

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.humboldthain.HumboldthainApiScraper.Companion.TICKET_URL_PATTERN
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

/** Public landing page every event links back to — the widget exposes no per-event URLs. */
private const val HUMBOLDTHAIN_URL = "https://www.humboldthain.com/"

/**
 * Pure parser for Humboldthain Club's programme, sourced from the JSON boot response
 * of the Elfsight "Event Calendar" widget embedded on its WordPress landing page
 * (`core.service.elfsight.com/p/boot/?w=<widgetId>`).
 *
 * The widget renders client-side, so the page itself carries no events; its boot API returns the whole
 * calendar as structured JSON — the most stable possible source (ADR-007 §"Selector Strategy" priority
 * 1) — without a headless browser. [HumboldthainWebsiteImporter] fetches the body; this class parses
 * it. The payload shape is shared with [de.norm.events.scraper.neuezukunft.NeueZukunftApiScraper]:
 * events nest under `data.widgets.<widgetId>.data.settings.events`, and the widget id is not
 * hard-coded — every embedded widget exposing a `settings.events` array contributes. Three things are
 * then specific to this venue.
 *
 * **Recurrences are expanded.** The resident night is a *single* entry carrying a weekly repeat rule
 * the widget expands in the browser, so reading only `start.date` would import it once at the series'
 * opening date — long past — and lose every upcoming occurrence. Weekly rules are expanded here into
 * one event per occurrence over a rolling [OCCURRENCE_HORIZON_WEEKS] horizon (bounded further by the
 * rule's own end date or count), which is why `sourceId` combines the widget id with the occurrence
 * date. Other frequencies (Elfsight's `nthDayInMonth` month rules) are **not** expanded — the venue
 * uses none — and contribute their start date alone.
 *
 * **Artists come from the description's links, not its prose.** The roster is written as
 * `ra.co/dj/<slug>` anchors inside the HTML `description`, a machine-readable list whose link text is
 * the DJ's name. The prose around them is not: its lineup headings vary night to night ("Lineup/Musik",
 * "Line-up Live:") and its other lines are door policy and awareness notes, so nothing is minted.
 *
 * **Every night is a party.** Events are typed [PARTY][EventType.PARTY] unless the title opens with
 * the venue's one category marker, `KONZERT:`, which is stripped and makes the remainder the
 * headliner. The widget's own `eventType` vocabulary is ignored: the venue has filled it with
 * weekday/time labels ("Samstag, 14:00") that contradict the event's own `start.time`. Prices appear
 * only in the prose, in too many spellings to parse, and nothing marks a night sold out, cancelled or
 * moved — so prices, `soldOut` and `status` are left unset.
 *
 * The widget returns the **whole calendar**, past nights included; those are dropped centrally at
 * persistence time (`EventUpsertService`), so non-recurring entries are returned as-is.
 *
 * @param clock supplies "today" for the recurrence horizon; override in tests for determinism.
 * @see HumboldthainWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.humboldthain.com/">Humboldthain Club</a>
 */
class HumboldthainApiScraper(
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    // Elfsight uses camelCase JSON keys (coverImage, isAllDay), so the default mapper suffices;
    // unknown fields are ignored (Jackson 3 default).
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses every event from the Elfsight widget boot response [json], expanding weekly
     * recurrences into one event per occurrence.
     *
     * @param json the raw JSON body of the `p/boot/?w=<widgetId>` response.
     * @return a list of [ScrapedEvent] instances; empty if the payload is absent, unparseable,
     *   or carries no events.
     */
    fun scrape(json: String): List<ScrapedEvent> {
        val eventNodes = parseEventNodes(json) ?: return emptyList()
        logger.info { "Found ${eventNodes.size} calendar entry/entries in Humboldthain widget response" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        val parsed =
            eventNodes.flatMap { node ->
                try {
                    parseEvent(jsonMapper.treeToValue(node, HumboldthainEventNode::class.java))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Humboldthain event, skipping" }
                    emptyList()
                }
            }

        // A series whose horizon overlaps a one-off entry of the same id would collide on sourceId.
        return parsed.distinctBy { it.sourceId }
    }

    /**
     * Walks the boot payload and returns the `events` nodes of every embedded widget that
     * exposes an event calendar, or null when the body is unparseable or carries no widgets.
     * The widget id keying `data.widgets` is not hard-coded — each widget node is inspected and
     * only those with a `settings.events` array (the `event-calendar` app) contribute events.
     */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed payload must degrade to null, never abort the import.
        "ReturnCount" // Guard clauses for the unparseable body and missing widgets are clearer than nesting.
    )
    private fun parseEventNodes(json: String): List<JsonNode>? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Humboldthain widget boot response" }
                return null
            }
        val widgets = root.path("data").path("widgets")
        if (!widgets.isObject) {
            logger.warn { "Humboldthain boot response has no 'data.widgets' object" }
            return null
        }
        return widgets.flatMap { widget ->
            widget.path("data").path("settings").path("events").let { events ->
                if (events.isArray) events.toList() else emptyList()
            }
        }
    }

    /** Validates one calendar entry and expands it into one [ScrapedEvent] per occurrence date. */
    @Suppress("ReturnCount") // Guard clauses for the required id, title, and date are clearer than nesting.
    private fun parseEvent(node: HumboldthainEventNode): List<ScrapedEvent> {
        val id = node.id.blankToNull()
        if (id == null) {
            logger.warn { "Humboldthain event has no id, skipping" }
            return emptyList()
        }

        val rawTitle = node.name.blankToNull()
        if (rawTitle == null) {
            logger.warn { "Humboldthain event '$id' has no name, skipping" }
            return emptyList()
        }

        val seriesStart = parseDate(node.start?.date)
        if (seriesStart == null) {
            logger.warn { "Humboldthain event '$id' has no parseable date, skipping" }
            return emptyList()
        }

        val concert = CONCERT_TITLE_PREFIX.containsMatchIn(rawTitle)
        val title = if (concert) rawTitle.replaceFirst(CONCERT_TITLE_PREFIX, "").trim().ifBlank { rawTitle } else rawTitle
        val descriptionHtml = node.description.blankToNull()
        val description = descriptionHtml?.let { Jsoup.parse(it) }

        // A "KONZERT:" night bills its act in the title; every other night is a DJ party whose
        // roster, if announced, is the description's Resident Advisor artist links.
        val artists = (if (concert) headlinersFromTitle(title) else emptyList()) + djArtists(description)
        val descriptionText = descriptionText(descriptionHtml)

        return occurrenceDates(node, id, seriesStart).map { date ->
            ScrapedEvent(
                title = title,
                description = descriptionText,
                eventType = if (concert) EventType.CONCERT.name else EventType.PARTY.name,
                eventDate = date,
                // All-day entries carry a placeholder time; only a real clock value becomes a start time.
                startTime = if (node.isAllDay) null else parseTime(node.start?.time.blankToNull()),
                imageUrl =
                    node.coverImage
                        ?.url
                        .blankToNull()
                        ?.takeIf { it.startsWith("http") },
                sourceUrl = HUMBOLDTHAIN_URL,
                sourceId = "${EventSource.HUMBOLDTHAIN.sourceIdPrefix}$id-$date",
                ticketUrl = ticketUrl(node.actions, description),
                artists = artists
            )
        }
    }

    /**
     * The dates this entry happens on: its own start date when it does not repeat, otherwise
     * every occurrence of its weekly rule from today over the rolling horizon.
     *
     * Only weekly rules are expanded, because they are the only kind the venue uses. Elfsight
     * files a monthly rule as `repeatFrequency: "daily"`/`"monthly"` with a `nthDayInMonth`
     * period, and guessing at its "second Wednesday" semantics would invent dates the venue
     * never announced — such an entry keeps its start date alone and is logged.
     */
    @Suppress("ReturnCount") // Guard clauses for the non-repeating and non-weekly cases are clearer than nesting.
    private fun occurrenceDates(
        node: HumboldthainEventNode,
        id: String,
        seriesStart: LocalDate
    ): List<LocalDate> {
        val period = node.repeatPeriod.blankToNull()?.lowercase()
        if (period == null || period == NO_REPEAT) return listOf(seriesStart)
        if (!node.repeatFrequency.equals(WEEKLY_FREQUENCY, ignoreCase = true)) {
            logger.warn {
                "Humboldthain event '$id' repeats '${node.repeatFrequency}' ($period), which is not expanded; keeping its start date only"
            }
            return listOf(seriesStart)
        }

        val weekdays =
            node.repeatWeeklyOnDays
                .mapNotNull { WEEKDAY_CODES[it.trim().lowercase()] }
                .ifEmpty { listOf(seriesStart.dayOfWeek) }
                .distinct()
                .sortedBy { it.value }
        val today = LocalDate.now(clock)
        // The horizon bounds an open-ended ("never") rule; an explicit end date shortens it.
        val limit = minOf(parseDate(node.repeatEndsDate?.date) ?: LocalDate.MAX, today.plusWeeks(OCCURRENCE_HORIZON_WEEKS))
        val interval = node.repeatInterval.coerceAtLeast(1).toLong()
        val skipped = node.exceptions.mapNotNull { exceptionDate(it) }.toSet()

        val occurrences =
            generateSequence(seriesStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(interval) }
                .takeWhile { it <= limit }
                .flatMap { weekStart -> weekdays.asSequence().map { weekStart.plusDays(it.value - 1L) } }
                .filter { it in seriesStart..limit }
        // An "after <n> occurrences" rule counts slots from the series start — so the cap applies
        // to the raw schedule, before a cancelled date is removed and the past is dropped.
        val capped =
            if (node.repeatEnds.equals(ENDS_AFTER_OCCURRENCES, ignoreCase = true)) {
                occurrences.take(node.repeatEndsOccurrences.coerceAtLeast(1))
            } else {
                occurrences
            }
        return capped.filter { it !in skipped && it >= today }.toList()
    }

    /**
     * The DJs billed on a night: the link text of every `ra.co/dj/<slug>` anchor in the
     * description, in document order, de-duplicated case-insensitively and filtered through the
     * shared [isNonArtistName] guard. Resident Advisor *event* links in the same prose are ticket
     * shops, not performers, and are matched by [ticketUrl] instead.
     */
    private fun djArtists(description: Document?): List<ScrapedArtist> =
        description
            ?.select(RA_ARTIST_LINK_SELECTOR)
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() && !isNonArtistName(it) }
            ?.distinctBy { it.lowercase() }
            ?.map { ScrapedArtist(name = it, role = ArtistRole.DJ.name) }
            .orEmpty()

    /**
     * The night's ticket-shop link: the widget's own "Presale Tickets" action when there is one,
     * otherwise a shop link the venue only wrote into the prose (a Resident Advisor event page or
     * an Eventim listing). A `ra.co/dj/` link is an artist profile and is excluded by requiring
     * the URL to match [TICKET_URL_PATTERN].
     */
    private fun ticketUrl(
        actions: List<HumboldthainAction>,
        description: Document?
    ): String? =
        actions
            .firstNotNullOfOrNull { it.link?.value.blankToNull() }
            ?.takeIf { it.startsWith("http") }
            ?: description
                ?.select("a[href]")
                ?.map { it.attr("href").trim() }
                ?.firstOrNull { it.startsWith("http") && TICKET_URL_PATTERN.containsMatchIn(it) }

    /**
     * Flattens the widget's HTML `description` into plain text, preserving paragraph breaks:
     * `<br>` and closing block tags become newlines before the remaining tags are stripped, then
     * blank lines are collapsed. Returns null for a missing/empty body.
     */
    private fun descriptionText(html: String?): String? {
        val raw = html.blankToNull() ?: return null
        val withBreaks = raw.replace(BLOCK_BREAK_PATTERN, "\n")
        return Jsoup
            .parse(withBreaks)
            .wholeText()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .blankToNull()
    }

    /**
     * The date a recurrence exception skips. Elfsight leaves `exceptions` empty on every entry
     * this venue publishes, so both plausible spellings are accepted — a bare ISO date string, or
     * the `{date, time}` object every other moment in the payload uses — rather than betting the
     * import on one.
     */
    private fun exceptionDate(node: JsonNode): LocalDate? = parseDate(if (node.isString) node.asString("") else node.path("date").asString(""))

    /** Parses an ISO `yyyy-MM-dd` date, returning null instead of throwing. */
    private fun parseDate(raw: String?): LocalDate? {
        val cleaned = raw.blankToNull() ?: return null
        return try {
            LocalDate.parse(cleaned)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        /**
         * How far ahead an open-ended weekly rule is expanded (~6 months of calendar).
         *
         * Deep enough that the club's resident night shows up in any month-ahead view, shallow
         * enough that the derived occurrences stay a plausible reading of the venue's own rule.
         * Every import regenerates the same rolling window; the stable `sourceId`
         * (`humboldthain:<id>-<date>`) makes that idempotent, and occurrences that roll out of the
         * window are cleaned up as stale by `EventUpsertService`.
         */
        const val OCCURRENCE_HORIZON_WEEKS: Long = 26

        /** Elfsight's `repeatPeriod` value for a one-off entry. */
        private const val NO_REPEAT = "norepeat"

        /** The only `repeatFrequency` this parser expands — see [occurrenceDates]. */
        private const val WEEKLY_FREQUENCY = "weekly"

        /** Elfsight's `repeatEnds` value capping a rule at a fixed number of occurrences. */
        private const val ENDS_AFTER_OCCURRENCES = "afterOccurrences"

        /** Elfsight's two-letter `repeatWeeklyOnDays` codes. */
        private val WEEKDAY_CODES: Map<String, DayOfWeek> =
            mapOf(
                "mo" to DayOfWeek.MONDAY,
                "tu" to DayOfWeek.TUESDAY,
                "we" to DayOfWeek.WEDNESDAY,
                "th" to DayOfWeek.THURSDAY,
                "fr" to DayOfWeek.FRIDAY,
                "sa" to DayOfWeek.SATURDAY,
                "su" to DayOfWeek.SUNDAY
            )

        /** The venue's one category marker, opening a title it wants read as a concert rather than a party. */
        private val CONCERT_TITLE_PREFIX = Regex("""^konzert\s*[:\-–—]\s*""", RegexOption.IGNORE_CASE)

        /** Resident Advisor **artist** profiles — the venue's machine-readable lineup markup. */
        private const val RA_ARTIST_LINK_SELECTOR = "a[href*=ra.co/dj/]"

        /** Ticket shops the venue links from its prose: a Resident Advisor event page, Eventim, or any "ticket" URL. */
        private val TICKET_URL_PATTERN = Regex("""ra\.co/events/|eventim|dice\.fm|ticket""", RegexOption.IGNORE_CASE)

        /** `<br>` variants and closing block tags — the boundaries turned into newlines before tag stripping. */
        private val BLOCK_BREAK_PATTERN = Regex("""(?i)<br\s*/?>|</div>|</p>""")
    }
}

/** Trims this string and returns `null` when it is null, empty, or all whitespace. */
private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

/**
 * One entry in the widget's `settings.events[]`, mapped from its JSON by Jackson.
 *
 * Only the fields Humboldthain populates are declared; unknown keys (styling, the empty
 * `location`/`host` lists, the venue's weekday-label `eventType`) are ignored. Every field is
 * nullable/defaulted so a partial or evolving payload deserializes cleanly and is validated in
 * [HumboldthainApiScraper] instead.
 */
private data class HumboldthainEventNode(
    val id: String? = null,
    val name: String? = null,
    val start: HumboldthainDateTime? = null,
    val description: String? = null,
    val isAllDay: Boolean = false,
    val coverImage: HumboldthainImage? = null,
    val actions: List<HumboldthainAction> = emptyList(),
    /** `noRepeat` for a one-off entry, `custom`/`nthDayInMonth` for a recurring series. */
    val repeatPeriod: String? = null,
    /** How the series repeats — only `weekly` is expanded (see [HumboldthainApiScraper]). */
    val repeatFrequency: String? = null,
    /** Repeat every *n*-th week; 1 for an ordinary weekly night. */
    val repeatInterval: Int = 1,
    /** Two-letter weekday codes the series runs on (`["tu"]`). */
    val repeatWeeklyOnDays: List<String> = emptyList(),
    /** `never`, `onDate` (see [repeatEndsDate]) or `afterOccurrences` (see [repeatEndsOccurrences]). */
    val repeatEnds: String? = null,
    val repeatEndsDate: HumboldthainDateTime? = null,
    val repeatEndsOccurrences: Int = 1,
    /** Dates skipped by the series; left as a raw node because the venue never populates it. */
    val exceptions: List<JsonNode> = emptyList()
)

/** A moment in the calendar: an ISO `date` (`yyyy-MM-dd`) and an `HH:mm` `time`. */
private data class HumboldthainDateTime(
    val date: String? = null,
    val time: String? = null
)

/** The event cover image; only its absolute [url] is used. */
private data class HumboldthainImage(
    val url: String? = null
)

/** A call-to-action button: its [text] (e.g. "Presale Tickets") and nested [link]. */
private data class HumboldthainAction(
    val text: String? = null,
    val link: HumboldthainLink? = null
)

/** The resolved target of a [HumboldthainAction]; empty for a non-linking marker. */
private data class HumboldthainLink(
    val value: String? = null
)
