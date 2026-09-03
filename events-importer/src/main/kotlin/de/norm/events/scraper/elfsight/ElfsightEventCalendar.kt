package de.norm.events.scraper.elfsight

import de.norm.events.scraper.blankToNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate
import java.time.format.DateTimeParseException

// Shared reader for the Elfsight "Event Calendar" widget, embedded by Humboldthain and Neue
// Zukunft. Both widgets render client-side, so neither landing page carries any event markup;
// the widget's boot API (`core.service.elfsight.com/p/boot/?w=<widgetId>`) returns the whole
// calendar as JSON instead (ADR-007 §"Prefer a JSON / API Source"). The payload shape and its
// readers live here; each venue keeps its own typing, artist and recurrence rules.

private val logger = KotlinLogging.logger {}

/**
 * The mapper every Elfsight payload is read with.
 *
 * Elfsight uses camelCase JSON keys (`coverImage`, `isAllDay`), so the default naming applies,
 * and unknown fields are ignored (Jackson 3 default).
 */
internal fun elfsightJsonMapper(): JsonMapper =
    JsonMapper
        .builder()
        .addModule(kotlinModule())
        .build()

/**
 * Walks the boot payload and returns the `events` nodes of every embedded widget that exposes
 * an event calendar, or `null` when the body is unparseable or carries no widgets.
 *
 * The widget id keying `data.widgets` is not hard-coded — each widget node is inspected and only
 * those with a `settings.events` array (the `event-calendar` app) contribute events. [venue]
 * names the venue in the warnings.
 */
@Suppress(
    "TooGenericExceptionCaught", // A malformed payload must degrade to null, never abort the import.
    "ReturnCount" // Guard clauses for the unparseable body and missing widgets are clearer than nesting.
)
internal fun parseElfsightEventNodes(
    mapper: JsonMapper,
    json: String,
    venue: String
): List<JsonNode>? {
    val root =
        try {
            mapper.readTree(json)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse $venue widget boot response" }
            return null
        }
    val widgets = root.path("data").path("widgets")
    if (!widgets.isObject) {
        logger.warn { "$venue boot response has no 'data.widgets' object" }
        return null
    }
    return widgets.flatMap { widget ->
        widget.path("data").path("settings").path("events").let { events ->
            if (events.isArray) events.toList() else emptyList()
        }
    }
}

/** Parses an ISO `yyyy-MM-dd` date from the payload, returning `null` instead of throwing. */
internal fun parseElfsightDate(raw: String?): LocalDate? {
    val cleaned = raw.blankToNull() ?: return null
    return try {
        LocalDate.parse(cleaned)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Flattens the widget's HTML `description` into plain text, preserving paragraph breaks.
 *
 * `<br>` and closing block tags become newlines before the remaining tags are stripped, then
 * blank lines are collapsed. Returns `null` for a missing or empty body.
 */
internal fun elfsightDescriptionText(html: String?): String? {
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
 * The first action link that is an absolute HTTP(S) URL — the widget's own ticket-shop button.
 *
 * A non-linking marker ("Sold Out!") carries an empty link and is skipped.
 */
internal fun elfsightActionUrl(actions: List<ElfsightAction>): String? =
    actions
        .firstNotNullOfOrNull { it.link?.value.blankToNull() }
        ?.takeIf { it.startsWith("http") }

/** `<br>` variants and closing block tags — the boundaries turned into newlines before tag stripping. */
private val BLOCK_BREAK_PATTERN = Regex("""(?i)<br\s*/?>|</div>|</p>""")

/**
 * One entry in the widget's `settings.events[]`, mapped from its JSON by Jackson.
 *
 * Only the fields the scraped venues populate are declared; unknown keys (styling, the empty
 * `location`/`host` lists) are ignored. Every field is nullable or defaulted so a partial or
 * evolving payload deserializes cleanly and is validated by the venue's parser instead.
 */
internal data class ElfsightEventNode(
    val id: String? = null,
    val name: String? = null,
    val start: ElfsightDateTime? = null,
    val description: String? = null,
    val isAllDay: Boolean = false,
    val coverImage: ElfsightImage? = null,
    val actions: List<ElfsightAction> = emptyList(),
    /** `noRepeat` for a one-off entry, `custom`/`nthDayInMonth` for a recurring series. */
    val repeatPeriod: String? = null,
    /** How the series repeats; only a venue that expands recurrences reads it. */
    val repeatFrequency: String? = null,
    /** Repeat every *n*-th week; 1 for an ordinary weekly night. */
    val repeatInterval: Int = 1,
    /** Two-letter weekday codes the series runs on (`["tu"]`). */
    val repeatWeeklyOnDays: List<String> = emptyList(),
    /** `never`, `onDate` (see [repeatEndsDate]) or `afterOccurrences` (see [repeatEndsOccurrences]). */
    val repeatEnds: String? = null,
    val repeatEndsDate: ElfsightDateTime? = null,
    val repeatEndsOccurrences: Int = 1,
    /** Dates the series skips; left as a raw node because no scraped venue populates it. */
    val exceptions: List<JsonNode> = emptyList()
)

/** A moment in the calendar: an ISO `date` (`yyyy-MM-dd`) and an `HH:mm` `time`. */
internal data class ElfsightDateTime(
    val date: String? = null,
    val time: String? = null
)

/** The event cover image; only its absolute [url] is used. */
internal data class ElfsightImage(
    val url: String? = null
)

/** A call-to-action button: its [text] (e.g. "Get Tickets", "Sold Out!") and nested [link]. */
internal data class ElfsightAction(
    val text: String? = null,
    val link: ElfsightLink? = null
)

/** The resolved target of an [ElfsightAction]; empty for a non-linking marker. */
internal data class ElfsightLink(
    val value: String? = null
)
