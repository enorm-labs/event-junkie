package de.norm.events.dataquality.flyer

import de.norm.events.ai.ModelAnswer
import de.norm.events.common.QuotedText
import de.norm.events.dataquality.classification.Finding
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalTime

/** What the model reads for one event: its title and day, and the flyer. Nothing else about the event leaves the platform. */
data class FlyerInput(
    val title: String,
    val eventDate: LocalDate,
    val image: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is FlyerInput && title == other.title && eventDate == other.eventDate && image.contentEquals(other.image)

    override fun hashCode(): Int = 31 * (31 * title.hashCode() + eventDate.hashCode()) + image.contentHashCode()
}

/** One validated answer: the text the model read off the flyer, and the acts and start time it found in that text. */
data class FlyerAnswer(
    val flyerText: String,
    val acts: Finding<List<String>>?,
    val startTime: Finding<LocalTime>?
)

/** Why a flyer answer was turned down. The pass logs it, because a rejected flyer is not read again (#5186). */
enum class FlyerRejection {
    NO_TEXT,
    REFUSED,
    NOT_JSON,
    NOT_AN_OBJECT,
    NO_FLYER_TEXT,
    FLYER_TEXT_TOO_LONG,
    DAY_NOT_NAMED
}

/** What [FlyerAnswerValidator] made of one answer. */
sealed interface FlyerVerdict {
    data class Accepted(
        val answer: FlyerAnswer
    ) : FlyerVerdict

    data class Rejected(
        val reason: FlyerRejection
    ) : FlyerVerdict
}

/**
 * Parses and checks one answer of [AnthropicFlyerReader] (ADR-059).
 *
 * The model copies the flyer's text into `flyerText`. Every act, the start time and each evidence passage must
 * occur in that text, and the text must name the event's day. Nothing proves that the image shows the text, so
 * the person who reviews the suggestion compares the two (ADR-049 rule 4).
 */
object FlyerAnswerValidator {
    /** Longer than a full festival flyer. A longer text is no copy of a flyer. */
    const val MAX_FLYER_TEXT = 3000

    /** More acts than this is a festival poster, and a lineup that long needs a person, not a suggestion. */
    const val MAX_ACTS = 20

    private const val MIN_ACT_LENGTH = 2

    /**
     * The answer, or the reason it fails: it is no object, carries no flyer text, or the text does not name the event's day.
     * A flyer that does not name the day may bill another night, so none of its acts is proposed.
     *
     * @throws de.norm.events.ai.UnparseableAnswer for text that is no JSON at all.
     */
    fun parse(
        raw: String,
        eventDate: LocalDate
    ): FlyerVerdict {
        val root = ModelAnswer.jsonObject(raw) ?: return FlyerVerdict.Rejected(FlyerRejection.NOT_AN_OBJECT)
        val flyerText =
            root
                .get("flyerText")
                ?.asString("")
                ?.trim()
                .orEmpty()
        val rejection =
            when {
                flyerText.isEmpty() -> FlyerRejection.NO_FLYER_TEXT
                flyerText.length > MAX_FLYER_TEXT -> FlyerRejection.FLYER_TEXT_TOO_LONG
                !EventDay.namedIn(eventDate, flyerText) -> FlyerRejection.DAY_NOT_NAMED
                else -> null
            }
        if (rejection != null) return FlyerVerdict.Rejected(rejection)
        val acts = finding(root.get("acts"), flyerText) { node -> acts(node, flyerText) }
        val startTime =
            finding(root.get("startTime"), flyerText) { node -> ModelAnswer.time(node.asString("")) }
                ?.takeIf { ModelAnswer.namesHour(it.value, it.evidence) }
        return FlyerVerdict.Accepted(FlyerAnswer(flyerText, acts, startTime))
    }

    private fun <T> finding(
        node: JsonNode?,
        flyerText: String,
        parse: (JsonNode) -> T?
    ): Finding<T>? {
        val field = node?.takeUnless { it.isNull } ?: return null
        val value = field.get("value")?.takeUnless { it.isNull }?.let(parse) ?: return null
        val evidence =
            field
                .get("evidence")
                ?.asString("")
                ?.trim()
                .orEmpty()
        val confidence = field.get("confidence")?.asDouble(-1.0) ?: -1.0
        val backed =
            confidence in 0.0..1.0 &&
                evidence.length in ModelAnswer.EVIDENCE_LENGTH &&
                QuotedText.eachLineOccursIn(evidence, flyerText)
        return if (backed) Finding(value, evidence, confidence) else null
    }

    /** The acts that occur in the flyer text, once each, in the model's order. Null when none is left. */
    private fun acts(
        node: JsonNode,
        flyerText: String
    ): List<String>? {
        if (!node.isArray) return null
        val seen = mutableSetOf<String>()
        return (0 until node.size())
            .map { node.get(it).asString("").trim() }
            .filter { it.length >= MIN_ACT_LENGTH && QuotedText.occursIn(it, flyerText) && seen.add(QuotedText.normalise(it)) }
            .take(MAX_ACTS)
            .ifEmpty { null }
    }
}

/** Whether a flyer's text names one day: "21.11.", "20.Nov", "19 NOVEMBER 2026", "SA/21/NOV", "NOV 17TH". */
internal object EventDay {
    /** The first letters of each month in German and English, after [QuotedText.normalise] folds `ä` to `a`. */
    private val MONTH_PREFIXES =
        listOf(
            listOf("jan"),
            listOf("feb"),
            listOf("mar"),
            listOf("apr"),
            listOf("mai", "may"),
            listOf("jun"),
            listOf("jul"),
            listOf("aug"),
            listOf("sep"),
            listOf("okt", "oct"),
            listOf("nov"),
            listOf("dez", "dec")
        )

    private const val ORDINAL = "(?:st|nd|rd|th)?"

    fun namedIn(
        date: LocalDate,
        text: String
    ): Boolean {
        val day = "0?${date.dayOfMonth}"
        val monthName = "(?:${MONTH_PREFIXES[date.monthValue - 1].joinToString("|")})\\p{L}*"
        val month = "(?:0?${date.monthValue}(?!\\d)|$monthName)"
        val dayFirst = Regex("(?<!\\d)$day$ORDINAL\\s*[./ -]\\s*$month")
        val monthFirst = Regex("(?<!\\p{L})$monthName\\.?\\s*$day$ORDINAL(?!\\d)")
        val normalised = QuotedText.normalise(text)
        return dayFirst.containsMatchIn(normalised) || monthFirst.containsMatchIn(normalised)
    }
}
