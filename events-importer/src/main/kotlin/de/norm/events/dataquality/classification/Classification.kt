package de.norm.events.dataquality.classification

import de.norm.events.event.EventType
import de.norm.events.event.PartyFeature
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalTime
import java.util.Locale

/** One cue the party-feature rules could not settle, as `event_quality_flag` stores it: `flinta-only: FLINTA* Party`. */
data class UncertainCue(
    val feature: PartyFeature,
    val phrase: String
)

/** The text the model reads for one event. Nothing else about the event leaves the platform (ADR-041). */
data class ClassificationInput(
    val title: String,
    val subtitle: String?,
    val description: String?,
    val uncertainCues: List<UncertainCue>
) {
    /** Every passage the model may quote, so evidence can be checked against it. */
    val text: String get() = listOfNotNull(title, subtitle, description).joinToString("\n")
}

/** One answer with the passage it rests on. */
data class Finding<T>(
    val value: T,
    val evidence: String,
    val confidence: Double
)

/** A ruling on one [UncertainCue]: [confirmed] says the night has the feature. */
data class FeatureVerdict(
    val feature: PartyFeature,
    val confirmed: Boolean,
    val evidence: String,
    val confidence: Double
)

/** What survived [ClassificationValidator]. A field the model left out, or one that failed a check, is null or absent. */
data class ClassificationAnswer(
    val eventType: Finding<EventType>? = null,
    /** The title words the model names as acts. An empty list says the title names no act. */
    val titleActs: Finding<List<String>>? = null,
    val features: List<FeatureVerdict> = emptyList(),
    val startTime: Finding<LocalTime>? = null,
    val pricePresale: Finding<BigDecimal>? = null,
    val priceBoxOffice: Finding<BigDecimal>? = null
)

/**
 * Turns the model's JSON into a [ClassificationAnswer], and drops every part that fails a check.
 *
 * The input is a venue's promotional copy, so the output is untrusted. A value must be in the closed
 * vocabulary, parse, and quote a passage that occurs in the input.
 */
object ClassificationValidator {
    private val logger = KotlinLogging.logger {}

    private val mapper: JsonMapper = JsonMapper.builder().build()

    private const val MIN_EVIDENCE_LENGTH = 3
    private const val MAX_EVIDENCE_LENGTH = 500
    private const val HALF_DAY = 12

    /** A price above this is a typo or a festival pass, and either way not a ticket price to propose. */
    private val MAX_PRICE = BigDecimal("500")

    private val CODE_FENCE = Regex("^```(?:json)?\\s*|\\s*```$")
    private val TIME = Regex("^([01]?\\d|2[0-3])[:.]([0-5]\\d)$")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("[‘’‛ʼ´`]")
    private val QUOTES = Regex("[“”„‟«»]")
    private val DASHES = Regex("[‐-―]")
    private val WHITESPACE = Regex("\\s+")

    /** The validated answer, or null when the text is no JSON object at all. */
    fun parse(
        raw: String,
        input: ClassificationInput
    ): ClassificationAnswer? {
        val root = readObject(raw) ?: return null
        val source = normalise(input.text)
        val dropped = mutableListOf<String>()

        fun <T> finding(
            name: String,
            parse: (JsonNode) -> T?
        ): Finding<T>? {
            val node = root.get(name)?.takeUnless { it.isNull } ?: return null
            val value = node.get("value")?.takeUnless { it.isNull }?.let(parse)
            val evidence = evidenceOf(node)
            val confidence = confidenceOf(node)
            return if (value != null && backed(evidence, confidence, source)) {
                Finding(value, evidence, confidence)
            } else {
                dropped += name
                null
            }
        }

        val answer =
            ClassificationAnswer(
                eventType = finding("eventType") { node -> EventType.entries.firstOrNull { it.name == node.asString("") } },
                titleActs = finding("titleActs") { node -> titleActs(node, input.title) },
                features = verdicts(root.get("features"), input, source, dropped),
                startTime = finding("startTime") { node -> time(node.asString("")) }?.takeIf(::namesHour),
                pricePresale = finding("pricePresale", ::price)?.takeIf { it.evidence.contains(it.value.toBigInteger().toString()) },
                priceBoxOffice = finding("priceBoxOffice", ::price)?.takeIf { it.evidence.contains(it.value.toBigInteger().toString()) }
            )
        if (dropped.isNotEmpty()) logger.warn { "Dropped ${dropped.size} classification answer(s) that failed a check: ${dropped.joinToString()}" }
        return answer
    }

    /** An act must be words of the title. A name the model made up or took from the description is refused. */
    private fun titleActs(
        node: JsonNode,
        title: String
    ): List<String>? {
        if (!node.isArray) return null
        val names = node.toList().map { it.asString("").trim() }
        val normalisedTitle = normalise(title)
        return names.takeIf { all -> all.all { it.isNotEmpty() && normalise(it) in normalisedTitle } }
    }

    private fun verdicts(
        node: JsonNode?,
        input: ClassificationInput,
        source: String,
        dropped: MutableList<String>
    ): List<FeatureVerdict> {
        if (node == null || !node.isArray) return emptyList()
        val asked = input.uncertainCues.map { it.feature }.toSet()
        return node.toList().mapNotNull { item ->
            val feature = PartyFeature.fromSlug(item.get("feature")?.asString(""))?.takeIf { it in asked }
            val confirmed = item.get("confirmed")?.takeIf { it.isBoolean }?.asBoolean()
            val evidence = evidenceOf(item)
            val confidence = confidenceOf(item)
            if (feature != null && confirmed != null && backed(evidence, confidence, source)) {
                FeatureVerdict(feature, confirmed, evidence, confidence)
            } else {
                dropped += "features"
                null
            }
        }
    }

    private fun time(value: String): LocalTime? =
        TIME.matchEntire(value.trim())?.let { match -> LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }

    /** The quote has to name the hour, on the 24-hour clock or the 12-hour one ("9 pm"). */
    private fun namesHour(finding: Finding<LocalTime>): Boolean {
        val hour = finding.value.hour
        val twelve = (hour % HALF_DAY).takeIf { it != 0 } ?: HALF_DAY
        return hour.toString() in finding.evidence || twelve.toString() in finding.evidence
    }

    private fun price(node: JsonNode): BigDecimal? {
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

    /** The object the model answered, or null for text that is no JSON object. */
    private fun readObject(raw: String): JsonNode? =
        try {
            mapper.readTree(raw.trim().replace(CODE_FENCE, "")).takeIf { it.isObject }
        } catch (e: JacksonException) {
            logger.warn(e) { "The classification answer is not JSON" }
            null
        }

    private fun evidenceOf(node: JsonNode): String =
        node
            .get("evidence")
            ?.asString("")
            ?.trim()
            .orEmpty()

    private fun confidenceOf(node: JsonNode): Double = node.get("confidence")?.asDouble(-1.0) ?: -1.0

    /** The quote occurs in the input, and the confidence is a probability. */
    private fun backed(
        evidence: String,
        confidence: Double,
        source: String
    ): Boolean =
        confidence in 0.0..1.0 &&
            evidence.length in MIN_EVIDENCE_LENGTH..MAX_EVIDENCE_LENGTH &&
            normalise(evidence) in source

    /** Case, accents, quote and dash shapes, and line breaks: what a faithful quote can still change. */
    fun normalise(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .replace(APOSTROPHES, "'")
            .replace(QUOTES, "\"")
            .replace(DASHES, "-")
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)
}
