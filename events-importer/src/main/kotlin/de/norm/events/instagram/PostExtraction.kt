package de.norm.events.instagram

import de.norm.events.common.QuotedText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** What the model reads for one post. Nothing else about the venue or the post leaves the platform. */
data class PostInput(
    val venueName: String,
    val caption: String,
    val postedAt: OffsetDateTime
) {
    /** The post's day in Berlin, which a caption's "Friday" and "next Saturday" count from. */
    val postedOn: LocalDate get() = postedAt.atZoneSameInstant(BERLIN).toLocalDate()

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

/** One event the model drafted from a caption, after [PostExtractionValidator] checked it. */
data class DraftedEvent(
    val date: LocalDate,
    val title: String,
    val startTime: LocalTime? = null,
    val acts: List<String> = emptyList(),
    val pricePresale: BigDecimal? = null,
    val priceBoxOffice: BigDecimal? = null,
    /** The passage of the caption the date rests on. */
    val evidence: String,
    val confidence: Double
)

/** What one extraction call gave, and the tokens it cost. */
sealed interface ExtractionResult {
    val tokens: Long

    /** The post announces these events. An empty list: it announces none, or none survived the checks. */
    data class Events(
        val events: List<DraftedEvent>,
        override val tokens: Long
    ) : ExtractionResult

    /** The model answered, and the answer was no JSON object or a refusal. Asking again gives the same answer. */
    data class Rejected(
        override val tokens: Long
    ) : ExtractionResult

    /** No answer came back: the call failed, or there is no key. The post is read again on the next pass. */
    data object Failed : ExtractionResult {
        override val tokens: Long = 0
    }
}

/**
 * Turns the model's JSON into [DraftedEvent]s, and drops every part that fails a check.
 *
 * A caption is a venue's promotional text, so the answer is untrusted. The date must quote the
 * caption, fall on the weekday the quote names, and lie between the post's day and [HORIZON_DAYS]
 * after it. An act, a start time and a price must each appear in the caption.
 */
object PostExtractionValidator {
    private val logger = KotlinLogging.logger {}

    private val mapper: JsonMapper = JsonMapper.builder().build()

    private const val MIN_EVIDENCE_LENGTH = 3
    private const val MAX_EVIDENCE_LENGTH = 500
    private const val MAX_TITLE_LENGTH = 200
    private const val HALF_DAY = 12

    /** A post announces a night months ahead at most. A date further out is a wrong year. */
    const val HORIZON_DAYS = 300L

    private val MAX_PRICE = BigDecimal("500")
    private val CODE_FENCE = Regex("^```(?:json)?\\s*|\\s*```$")
    private val TIME = Regex("^([01]?\\d|2[0-3])[:.]([0-5]\\d)$")

    /** Whole names only: "so", "do" and "sun" are words, and a false weekday would drop a right date. */
    private val WEEKDAYS: Map<DayOfWeek, Regex> =
        mapOf(
            DayOfWeek.MONDAY to "monday|montag",
            DayOfWeek.TUESDAY to "tuesday|dienstag",
            DayOfWeek.WEDNESDAY to "wednesday|mittwoch",
            DayOfWeek.THURSDAY to "thursday|donnerstag",
            DayOfWeek.FRIDAY to "friday|freitag",
            DayOfWeek.SATURDAY to "saturday|samstag|sonnabend",
            DayOfWeek.SUNDAY to "sunday|sonntag"
        ).mapValues { (_, names) -> Regex("(?<![\\p{L}])(?:$names)(?![\\p{L}])", RegexOption.IGNORE_CASE) }

    /** Null when the answer is no JSON object. */
    fun parse(
        raw: String,
        input: PostInput,
        minConfidence: Double
    ): List<DraftedEvent>? {
        val root = readObject(raw) ?: return null
        val events =
            root
                .path("events")
                .takeIf { it.isArray && root.path("isEvent").asBoolean(false) }
                ?.toList()
                .orEmpty()
        val kept = events.mapNotNull { event(it, input, minConfidence) }
        if (kept.size < events.size) logger.warn { "Dropped ${events.size - kept.size} of ${events.size} drafted event(s) that failed a check" }
        // A post that names one night twice ("Friday … October 16th") gives one draft.
        return kept.groupBy { it.date }.map { (_, same) -> same.maxBy { it.confidence } }
    }

    private fun event(
        node: JsonNode,
        input: PostInput,
        minConfidence: Double
    ): DraftedEvent? {
        val caption = input.caption
        val evidence = node.path("evidence").asString("").trim()
        val confidence = node.path("confidence").asDouble(-1.0)
        val date = date(node.path("date").asString(""))
        val title = node.path("title").asString("").trim()
        val valid =
            date != null &&
                title.length in 1..MAX_TITLE_LENGTH &&
                confidence in minConfidence..1.0 &&
                evidence.length in MIN_EVIDENCE_LENGTH..MAX_EVIDENCE_LENGTH &&
                QuotedText.occursIn(evidence, caption) &&
                date in input.postedOn..input.postedOn.plusDays(HORIZON_DAYS) &&
                weekdayAgrees(evidence, date)
        if (!valid) return null
        return DraftedEvent(
            date = checkNotNull(date),
            title = title,
            startTime = time(node.path("startTime").asString(""))?.takeIf { namesHour(it, caption) },
            acts =
                node
                    .path("acts")
                    .toList()
                    .map { it.asString("").trim() }
                    .filter { it.isNotEmpty() && QuotedText.occursIn(it, caption) }
                    .distinct(),
            pricePresale = price(node.get("pricePresale"))?.takeIf { it.toBigInteger().toString() in caption },
            priceBoxOffice = price(node.get("priceBoxOffice"))?.takeIf { it.toBigInteger().toString() in caption },
            evidence = evidence,
            confidence = confidence
        )
    }

    /** A quote that names a weekday must name the one the date falls on. A quote that names none passes. */
    private fun weekdayAgrees(
        evidence: String,
        date: LocalDate
    ): Boolean {
        val named = WEEKDAYS.filterValues { it.containsMatchIn(evidence) }.keys
        return named.isEmpty() || date.dayOfWeek in named
    }

    private fun date(value: String): LocalDate? =
        try {
            LocalDate.parse(value.trim())
        } catch (_: DateTimeParseException) {
            null
        }

    private fun time(value: String): LocalTime? =
        TIME.matchEntire(value.trim())?.let { match -> LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }

    /** The caption has to name the hour, on the 24-hour clock or the 12-hour one ("9pm"). */
    private fun namesHour(
        time: LocalTime,
        caption: String
    ): Boolean {
        val twelve = (time.hour % HALF_DAY).takeIf { it != 0 } ?: HALF_DAY
        return listOf(time.hour, twelve).any { Regex("(?<!\\d)$it(?!\\d)").containsMatchIn(caption) }
    }

    private fun price(node: JsonNode?): BigDecimal? {
        if (node == null || node.isNull) return null
        val value =
            if (node.isNumber) {
                node.decimalValue()
            } else {
                node
                    .asString("")
                    .trim()
                    .replace(',', '.')
                    .toBigDecimalOrNull()
            }
        return value?.takeIf { it > BigDecimal.ZERO && it <= MAX_PRICE }?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun readObject(raw: String): JsonNode? =
        try {
            mapper.readTree(raw.trim().replace(CODE_FENCE, "")).takeIf { it.isObject }
        } catch (e: JacksonException) {
            logger.warn(e) { "The extraction answer is not JSON" }
            null
        }
}
