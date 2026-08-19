package de.norm.events.scraper.neuezukunft

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Public landing page every event links back to — the widget exposes no per-event URLs. */
private const val NEUE_ZUKUNFT_URL = "https://neue-zukunft.org/"

/**
 * Pure parser for Neue Zukunft's concert programme, sourced from the JSON boot
 * response of the Elfsight "Event Calendar" widget embedded on its landing page
 * (`core.service.elfsight.com/p/boot/?w=<widgetId>`).
 *
 * The public page (`neue-zukunft.org`) is a static landing page: the concert
 * programme is otherwise published only as an image-based monthly PDF poster, and
 * the widget renders client-side, so neither is scrapeable as HTML. The widget's
 * boot API, however, returns every event as clean structured JSON — the most stable
 * possible source (ADR-007 §"Selector Strategy" priority 1). [NeueZukunftWebsiteImporter]
 * fetches the response body; this class parses it.
 *
 * The payload nests the events under `data.widgets.<widgetId>.data.settings.events`; the widget id
 * is not hard-coded — every embedded `event-calendar` widget's events are collected. Each event
 * carries an `id`, `name`, a `start.{date,time}`, an HTML `description`, a `coverImage.url`, and
 * `actions[]` (a "Get Tickets" link, or a "Sold Out!" marker with an empty link). Neue Zukunft is
 * a live-music venue with no event-category field, so the type defaults to `CONCERT`, flipping to
 * `FESTIVAL` only for an unambiguous festival title ([isFestivalTitle]).
 *
 * The widget returns the venue's **whole calendar**, including shows that have already
 * happened; those past-dated events are dropped centrally at persistence time
 * (`EventUpsertService`), so this parser returns every calendar entry as-is.
 *
 * @see NeueZukunftWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://neue-zukunft.org/">Neue Zukunft</a>
 */
class NeueZukunftApiScraper {
    private val logger = KotlinLogging.logger {}

    // Elfsight uses camelCase JSON keys (coverImage, isAllDay), so the default mapper suffices;
    // unknown fields are ignored (Jackson 3 default).
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses every event from the Elfsight widget boot response [json].
     *
     * @param json the raw JSON body of the `p/boot/?w=<widgetId>` response.
     * @return a list of [ScrapedEvent] instances, one per calendar entry; empty if the
     *   payload is absent, unparseable, or carries no events.
     */
    fun scrape(json: String): List<ScrapedEvent> {
        val eventNodes = parseEventNodes(json) ?: return emptyList()
        logger.info { "Found ${eventNodes.size} event(s) in Neue Zukunft widget response" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        val parsed =
            eventNodes.mapNotNull { node ->
                try {
                    parseEvent(jsonMapper.treeToValue(node, NeueZukunftEventNode::class.java))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Neue Zukunft event, skipping" }
                    null
                }
            }

        return parsed
    }

    /**
     * Walks the boot payload and returns the `events` nodes of every embedded widget
     * that exposes an event calendar, or null when the body is unparseable or carries no
     * widgets. The widget id keying `data.widgets` is not hard-coded — each widget node is
     * inspected and only those with a `settings.events` array (the `event-calendar` app)
     * contribute events. `JsonNode` iterates its object values, so the keys are irrelevant.
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
                logger.warn(e) { "Failed to parse Neue Zukunft widget boot response" }
                return null
            }
        val widgets = root.path("data").path("widgets")
        if (!widgets.isObject) {
            logger.warn { "Neue Zukunft boot response has no 'data.widgets' object" }
            return null
        }
        return widgets.flatMap { widget ->
            widget.path("data").path("settings").path("events").let { events ->
                if (events.isArray) events.toList() else emptyList()
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required id, title, and date are clearer than nesting.
    private fun parseEvent(node: NeueZukunftEventNode): ScrapedEvent? {
        val id = node.id.blankToNull()
        if (id == null) {
            logger.warn { "Neue Zukunft event has no id, skipping" }
            return null
        }

        val title = node.name.blankToNull()
        if (title == null) {
            logger.warn { "Neue Zukunft event '$id' has no name, skipping" }
            return null
        }

        val eventDate = parseDate(node.start?.date)
        if (eventDate == null) {
            logger.warn { "Neue Zukunft event '$id' has no parseable date, skipping" }
            return null
        }

        // All-day entries carry a placeholder time; only a real clock value becomes a start time.
        val startTime = if (node.isAllDay) null else parseTime(node.start?.time.blankToNull())

        val festival = isFestivalTitle(title)
        val eventType = if (festival) EventType.FESTIVAL.name else EventType.CONCERT.name

        return ScrapedEvent(
            title = title,
            description = htmlToText(node.description),
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            imageUrl =
                node.coverImage
                    ?.url
                    .blankToNull()
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = NEUE_ZUKUNFT_URL,
            sourceId = "${EventSource.NEUE_ZUKUNFT.sourceIdPrefix}$id",
            ticketUrl = ticketUrl(node.actions),
            soldOut = node.actions.any { it.text.blankToNull()?.contains("sold out", ignoreCase = true) == true },
            // A festival title names an event, not a performer; only concerts mint headliners from the title.
            artists = if (festival) emptyList() else headlinersFromTitle(title)
        )
    }

    /** First action link that is an absolute HTTP(S) URL (the "Get Tickets" shop link); a "Sold Out!" marker has an empty link. */
    private fun ticketUrl(actions: List<NeueZukunftAction>): String? =
        actions
            .firstNotNullOfOrNull { it.link?.value.blankToNull() }
            ?.takeIf { it.startsWith("http") }

    /** Parses an ISO `yyyy-MM-dd` date, returning null instead of throwing. */
    private fun parseDate(raw: String?): LocalDate? {
        val cleaned = raw.blankToNull() ?: return null
        return try {
            LocalDate.parse(cleaned)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Flattens the widget's HTML `description` into plain text, preserving paragraph
     * breaks: `<br>` and closing block tags become newlines before the remaining tags
     * are stripped, then blank lines are collapsed. Returns null for a missing/empty body.
     */
    private fun htmlToText(html: String?): String? {
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

    private companion object {
        /** `<br>` variants and closing block tags — the boundaries turned into newlines before tag stripping. */
        val BLOCK_BREAK_PATTERN = Regex("""(?i)<br\s*/?>|</div>|</p>""")
    }
}

/** Trims this string and returns `null` when it is null, empty, or all whitespace. */
private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

/**
 * One entry in the widget's `settings.events[]`, mapped from its JSON by Jackson.
 *
 * Only the fields Neue Zukunft populates are declared; unknown keys (repeat rules,
 * styling, empty `location`/`host`) are ignored. Every field is nullable/defaulted so a
 * partial or evolving payload deserializes cleanly and is validated in
 * [NeueZukunftApiScraper.parseEvent] instead.
 */
private data class NeueZukunftEventNode(
    val id: String? = null,
    val name: String? = null,
    val start: NeueZukunftDateTime? = null,
    val description: String? = null,
    val isAllDay: Boolean = false,
    val coverImage: NeueZukunftImage? = null,
    val actions: List<NeueZukunftAction> = emptyList()
)

/** The event's start moment: an ISO `date` (`yyyy-MM-dd`) and an `HH:mm` `time`. */
private data class NeueZukunftDateTime(
    val date: String? = null,
    val time: String? = null
)

/** The event cover image; only its absolute [url] is used. */
private data class NeueZukunftImage(
    val url: String? = null
)

/** A call-to-action button: its [text] (e.g. "Get Tickets", "Sold Out!") and nested [link]. */
private data class NeueZukunftAction(
    val text: String? = null,
    val link: NeueZukunftLink? = null
)

/** The resolved target of an [NeueZukunftAction]; empty for a non-linking marker like "Sold Out!". */
private data class NeueZukunftLink(
    val value: String? = null
)
