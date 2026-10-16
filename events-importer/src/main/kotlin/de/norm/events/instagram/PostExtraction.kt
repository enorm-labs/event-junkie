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

/** What the model reads for one post. Nothing else about the venue or the post leaves the platform. [caption] is empty for a post without one. */
data class PostInput(
    val venueName: String,
    val caption: String,
    val postedAt: OffsetDateTime,
    val images: List<PostImage> = emptyList()
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
    val doorsTime: LocalTime? = null,
    val acts: List<String> = emptyList(),
    val pricePresale: BigDecimal? = null,
    val priceBoxOffice: BigDecimal? = null,
    /** The passage of the caption or the image text the date rests on. */
    val evidence: String,
    val confidence: Double,
    /** The text the model read off the post's images, word for word. Null when no image was sent. */
    val flyerText: String? = null
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
 * after it. A start time, a doors time and a price must each appear in the caption. An act must appear in it too,
 * or spell a handle in it.
 *
 * When images were sent, the model also copies the text it reads on them into `flyerText`, and every
 * check accepts that text beside the caption (ADR-049). Nothing here can prove the image says it: the
 * person who approves the draft compares the flyer text with the post.
 */
object PostExtractionValidator {
    private val logger = KotlinLogging.logger {}

    private val mapper: JsonMapper = JsonMapper.builder().build()

    private const val MIN_EVIDENCE_LENGTH = 3
    private const val MAX_EVIDENCE_LENGTH = 500
    private const val MAX_TITLE_LENGTH = 200

    /** A flyer with a long lineup is a few hundred characters. More is not a copy of a flyer. */
    private const val MAX_FLYER_TEXT_LENGTH = 3000
    private const val HALF_DAY = 12

    /** A post announces a night months ahead at most. A date further out is a wrong year. */
    const val HORIZON_DAYS = 300L

    private val MAX_PRICE = BigDecimal("500")
    private val CODE_FENCE = Regex("^```(?:json)?\\s*|\\s*```$")
    private val TIME = Regex("^([01]?\\d|2[0-3])[:.]([0-5]\\d)$")
    private val HANDLE = Regex("[\\p{L}\\p{N}._]+")
    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]")
    private const val MIN_NAME_LETTERS = 3

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
        val flyerText = flyerText(root, input)
        val kept = events.mapNotNull { event(it, input, flyerText, minConfidence) }
        if (kept.size < events.size) logger.warn { "Dropped ${events.size - kept.size} of ${events.size} drafted event(s) that failed a check" }
        // A post that names one night twice ("Friday … October 16th") gives one draft.
        return kept.groupBy { it.date }.map { (_, same) -> same.maxBy { it.confidence } }
    }

    /** Read only when images were sent, so a caption-only answer cannot widen its own checks. */
    private fun flyerText(
        root: JsonNode,
        input: PostInput
    ): String? =
        root
            .path("flyerText")
            .asString("")
            .trim()
            .takeIf { input.images.isNotEmpty() && it.isNotEmpty() }
            ?.take(MAX_FLYER_TEXT_LENGTH)

    private fun event(
        node: JsonNode,
        input: PostInput,
        flyerText: String?,
        minConfidence: Double
    ): DraftedEvent? {
        // Everything the model read: a fact must be quoted from one of the two.
        val caption = listOfNotNull(input.caption.takeIf { it.isNotBlank() }, flyerText).joinToString("\n")
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
            doorsTime = time(node.path("doorsTime").asString(""))?.takeIf { namesHour(it, caption) },
            acts =
                node
                    .path("acts")
                    .toList()
                    .map { it.asString("").trim() }
                    .filter { it.isNotEmpty() && namedIn(it, caption) }
                    .distinct(),
            pricePresale = price(node.get("pricePresale"))?.takeIf { it.toBigInteger().toString() in caption },
            priceBoxOffice = price(node.get("priceBoxOffice"))?.takeIf { it.toBigInteger().toString() in caption },
            evidence = evidence,
            confidence = confidence,
            flyerText = flyerText
        )
    }

    /** The act as written, or the name a handle spells: "henningbaer_" as "Henning Baer", "amoral___dj" as "Amoral". */
    private fun namedIn(
        act: String,
        caption: String
    ): Boolean {
        if (QuotedText.occursIn(act, caption)) return true
        val name = letters(act)
        // Half the handle at least, so a short fragment of an unrelated handle does not pass as a name.
        return name.length >= MIN_NAME_LETTERS &&
            HANDLE.findAll(caption).map { letters(it.value) }.any { name in it && name.length * 2 >= it.length }
    }

    private fun letters(text: String): String = QuotedText.normalise(text).replace(NOT_LETTER_OR_DIGIT, "")

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
