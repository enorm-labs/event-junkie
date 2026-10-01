package de.norm.events.scraper.elfsight

import de.norm.events.scraper.blankToNull
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

// Shared reader for the Elfsight "Event Calendar" widget, embedded by Humboldthain and Neue
// Zukunft. Both widgets render client-side, so neither landing page carries event markup; the
// widget's boot API (`core.service.elfsight.com/p/boot/?w=<widgetId>`) returns the whole
// calendar as JSON (ADR-007 §"Prefer a JSON / API Source"). The payload shape and its readers
// live here, and so does the expansion of a recurring entry; each venue keeps its own typing and
// artist rules.

private val logger = KotlinLogging.logger {}

/**
 * The mapper every Elfsight payload is read with. Elfsight uses camelCase JSON keys
 * (`coverImage`, `isAllDay`), so the default naming applies, and unknown fields are ignored
 * (Jackson 3 default).
 */
internal fun elfsightJsonMapper(): JsonMapper =
    JsonMapper
        .builder()
        .addModule(kotlinModule())
        .build()

/**
 * Walks the boot payload and returns the `events` nodes of every embedded widget exposing an
 * event calendar, or `null` when unparseable or without widgets. The widget id keying
 * `data.widgets` is not hard-coded — each widget node is inspected and only those with a
 * `settings.events` array (the `event-calendar` app) contribute. [venue] names the venue in
 * the warnings.
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

/** Parses an ISO `yyyy-MM-dd` date from the payload, `null` instead of throwing. */
internal fun parseElfsightDate(raw: String?): LocalDate? {
    val cleaned = raw.blankToNull() ?: return null
    return try {
        LocalDate.parse(cleaned)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * The first action link that is an absolute HTTP(S) URL — the widget's own ticket-shop button.
 * A non-linking marker ("Sold Out!") carries an empty link and is skipped.
 */
internal fun elfsightActionUrl(actions: List<ElfsightAction>): String? =
    actions
        .firstNotNullOfOrNull { it.link?.value.blankToNull() }
        ?.takeIf { it.startsWith("http") }

/**
 * How far ahead an open-ended rule is expanded (~6 months). Deep enough that a resident night
 * shows up in any month-ahead view, shallow enough that the derived occurrences stay a plausible
 * reading of the venue's rule. Every import regenerates the same rolling window; a `sourceId`
 * carrying the occurrence date makes that idempotent, and occurrences rolling out of the window
 * are cleaned up as stale by `EventUpsertService`.
 */
internal const val OCCURRENCE_HORIZON_WEEKS: Long = 26

/** Whether the entry carries a recurrence rule rather than a single date. */
internal fun ElfsightEventNode.repeats(): Boolean = repeatPeriod.blankToNull()?.lowercase().let { it != null && it != NO_REPEAT }

/**
 * The dates [node] happens on from [today]: its [seriesStart] when it does not repeat, otherwise
 * every occurrence of its rule over the rolling [OCCURRENCE_HORIZON_WEEKS], bounded further by the
 * rule's own end date or count, minus the dates it skips. [venue] and [id] name it in the warning.
 *
 * Two rule shapes are expanded, the two the venues publish:
 * - **weekly** (`repeatFrequency: weekly`) on the listed weekdays, every `repeatInterval` weeks —
 *   Humboldthain's resident night;
 * - **monthly on the start date's nth weekday**: `repeatPeriod: nthDayInMonth`, whatever
 *   `repeatFrequency` and `repeatMonthlyOnDay` say, or `custom` with `monthly` and `nthDay`.
 *   Neue Zukunft's "every 1st and 3rd Wednesday" is two such entries, and the widget renders
 *   exactly these dates (#333).
 *
 * Any other rule, and a fifth weekday that some months lack, keeps its start date and is logged:
 * a guess would invent dates the venue never announced.
 */
@Suppress("ReturnCount") // Guard clauses for the non-repeating and unknown rules are clearer than nesting.
internal fun elfsightOccurrenceDates(
    node: ElfsightEventNode,
    seriesStart: LocalDate,
    today: LocalDate,
    venue: String,
    id: String
): List<LocalDate> {
    if (!node.repeats()) return listOf(seriesStart)
    val slots =
        when {
            node.repeatFrequency.equals(WEEKLY_FREQUENCY, ignoreCase = true) -> weeklySlots(node, seriesStart)
            node.isNthWeekdayRule() && seriesStart.weekdayOrdinal() <= MAX_WEEKDAY_ORDINAL -> monthlySlots(node, seriesStart)
            else -> null
        }
    if (slots == null) {
        logger.warn {
            "$venue event '$id' repeats '${node.repeatFrequency}' (${node.repeatPeriod}, ${node.repeatMonthlyOnDay}) from $seriesStart, " +
                "which is not expanded; keeping its start date only"
        }
        return listOf(seriesStart)
    }

    // The horizon bounds an open-ended ("never") rule; an explicit end date shortens it.
    val limit = minOf(parseElfsightDate(node.repeatEndsDate?.date) ?: LocalDate.MAX, today.plusWeeks(OCCURRENCE_HORIZON_WEEKS))
    val occurrences = slots.takeWhile { it <= limit }.filter { it >= seriesStart }
    // An "after <n> occurrences" rule counts slots from the series start — the cap applies to the
    // raw schedule, before a cancelled date is removed and the past is dropped.
    val capped =
        if (node.repeatEnds.equals(ENDS_AFTER_OCCURRENCES, ignoreCase = true)) {
            occurrences.take(node.repeatEndsOccurrences.coerceAtLeast(1))
        } else {
            occurrences
        }
    val skipped = node.exceptions.mapNotNull { exceptionDate(it) }.toSet()
    return capped.filter { it !in skipped && it >= today }.toList()
}

private fun ElfsightEventNode.isNthWeekdayRule(): Boolean {
    val period = repeatPeriod.blankToNull()?.lowercase()
    return period == NTH_DAY_IN_MONTH ||
        (
            period == CUSTOM &&
                repeatFrequency.equals(MONTHLY_FREQUENCY, ignoreCase = true) &&
                repeatMonthlyOnDay.equals(NTH_DAY, ignoreCase = true)
        )
}

/** Which of its month's weekdays of that name a date is: 1 for the first Wednesday, 3 for the third. */
private fun LocalDate.weekdayOrdinal(): Int = (dayOfMonth - 1) / DAYS_PER_WEEK + 1

/** Every slot of a weekly rule from the series' first week on, ascending and unbounded. */
private fun weeklySlots(
    node: ElfsightEventNode,
    seriesStart: LocalDate
): Sequence<LocalDate> {
    val weekdays =
        node.repeatWeeklyOnDays
            .mapNotNull { WEEKDAY_CODES[it.trim().lowercase()] }
            .ifEmpty { listOf(seriesStart.dayOfWeek) }
            .distinct()
            .sortedBy { it.value }
    val interval = node.repeatInterval.coerceAtLeast(1).toLong()
    return generateSequence(seriesStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(interval) }
        .flatMap { weekStart -> weekdays.asSequence().map { weekStart.plusDays(it.value - 1L) } }
}

/** The start date's nth weekday in every `repeatInterval`-th month, ascending and unbounded. */
private fun monthlySlots(
    node: ElfsightEventNode,
    seriesStart: LocalDate
): Sequence<LocalDate> {
    val adjuster = TemporalAdjusters.dayOfWeekInMonth(seriesStart.weekdayOrdinal(), seriesStart.dayOfWeek)
    val interval = node.repeatInterval.coerceAtLeast(1).toLong()
    return generateSequence(seriesStart.withDayOfMonth(1)) { it.plusMonths(interval) }.map { it.with(adjuster) }
}

/**
 * The date a recurrence exception skips. Elfsight leaves `exceptions` empty on every entry the
 * venues publish, so both plausible spellings are accepted — a bare ISO date string, or the
 * `{date, time}` object every other moment uses — rather than betting the import on one.
 */
private fun exceptionDate(node: JsonNode): LocalDate? = parseElfsightDate(if (node.isString) node.asString("") else node.path("date").asString(""))

/** Elfsight's `repeatPeriod` value for a one-off entry. */
private const val NO_REPEAT = "norepeat"

/** `repeatPeriod` values of a monthly nth-weekday rule: the preset, and the custom rule. */
private const val NTH_DAY_IN_MONTH = "nthdayinmonth"
private const val CUSTOM = "custom"

private const val WEEKLY_FREQUENCY = "weekly"
private const val MONTHLY_FREQUENCY = "monthly"

/** `repeatMonthlyOnDay` of a custom monthly rule on the nth weekday rather than the same day of the month. */
private const val NTH_DAY = "nthDay"

/** Elfsight's `repeatEnds` value capping a rule at a fixed number of occurrences. */
private const val ENDS_AFTER_OCCURRENCES = "afterOccurrences"

/** The last weekday ordinal every month has; a fifth Wednesday is missing from most months. */
private const val MAX_WEEKDAY_ORDINAL = 4

private const val DAYS_PER_WEEK = 7

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

/**
 * One entry in the widget's `settings.events[]`, mapped by Jackson. Only the fields the
 * scraped venues populate are declared; unknown keys (styling, the empty `location`/`host`
 * lists) are ignored. Every field is nullable or defaulted so a partial or evolving payload
 * deserializes and is validated by the venue's parser instead.
 */
internal data class ElfsightEventNode(
    val id: String? = null,
    val name: String? = null,
    val start: ElfsightDateTime? = null,
    val description: String? = null,
    val isAllDay: Boolean = false,
    val coverImage: ElfsightImage? = null,
    val images: List<ElfsightImage> = emptyList(),
    val actions: List<ElfsightAction> = emptyList(),
    /** `noRepeat` for a one-off entry, `custom`/`nthDayInMonth` for a recurring series. */
    val repeatPeriod: String? = null,
    /** How the series repeats: `weekly`, `monthly`, or a stale `daily` under `nthDayInMonth`. */
    val repeatFrequency: String? = null,
    /** `nthDay` or `sameDay`; read only by a `custom` monthly rule, as the `nthDayInMonth` preset ignores it. */
    val repeatMonthlyOnDay: String? = null,
    /** Repeat every *n*-th week or month; 1 for an ordinary weekly or monthly night. */
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
