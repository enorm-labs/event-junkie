package de.norm.events.dataquality.classification

import de.norm.events.ai.ModelAnswer
import de.norm.events.common.QuotedText
import de.norm.events.event.EventFeature
import de.norm.events.event.EventType
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalTime
import java.util.Locale

/** One cue the event-feature rules could not settle, as `event_quality_flag` stores it: `flinta-only: FLINTA* Party`. */
data class UncertainCue(
    val feature: EventFeature,
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
    val feature: EventFeature,
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

    /** The validated answer, or null when the text is no JSON object at all. */
    fun parse(
        raw: String,
        input: ClassificationInput
    ): ClassificationAnswer? {
        val root = readObject(raw) ?: return null
        val source = QuotedText.normalise(input.text)
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
                startTime = finding("startTime") { node -> ModelAnswer.time(node.asString("")) }?.takeIf { ModelAnswer.namesHour(it.value, it.evidence) },
                pricePresale = finding("pricePresale", ModelAnswer::price)?.takeIf { ModelAnswer.namesPrice(it.value, it.evidence) },
                priceBoxOffice = finding("priceBoxOffice", ModelAnswer::price)?.takeIf { ModelAnswer.namesPrice(it.value, it.evidence) }
            )
        if (dropped.isNotEmpty()) logger.warn { "Dropped ${dropped.size} classification answer(s) that failed a check: ${dropped.joinToString()}" }
        return answer
    }

    /** An act must be a whole name in the title. A name the model made up or took from the description is refused. */
    private fun titleActs(
        node: JsonNode,
        title: String
    ): List<String>? {
        if (!node.isArray) return null
        val names = node.toList().map { it.asString("").trim() }
        return names.takeIf { all -> all.all { it.isNotEmpty() && TitleActNames.isWholeName(it, title) } }
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
            val feature = EventFeature.fromSlug(item.get("feature")?.asString(""))?.takeIf { it in asked }
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

    /** The object the model answered, or null for text that is no JSON object. */
    private fun readObject(raw: String): JsonNode? =
        try {
            ModelAnswer.jsonObject(raw)
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
            evidence.length in ModelAnswer.EVIDENCE_LENGTH &&
            QuotedText.normalise(evidence) in source
}

/** Whether a proposed act is a whole name in the title, and not cut short: "Katja" from "Katja Berlin Tour 2026" (#2997). */
private object TitleActNames {
    private const val NO_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val NO_WORD_AFTER = "(?![\\p{L}\\p{N}])"

    /** Capitalised words that may follow a complete name: a tour or edition suffix, or a word that joins two acts. */
    private val NAME_SUFFIXES = setOf("tour", "tournee", "live", "edition", "feat", "featuring", "und", "and", "mit", "with", "vs")

    fun isWholeName(
        act: String,
        title: String
    ): Boolean {
        val normalisedAct = QuotedText.normalise(act)
        val wholeWords = Regex("$NO_WORD_BEFORE${Regex.escape(normalisedAct)}$NO_WORD_AFTER")
        return wholeWords.containsMatchIn(QuotedText.normalise(title)) && !cutShort(act, title)
    }

    /**
     * Every occurrence of [act] goes on with a capitalised word that is no suffix, so the name continues.
     * An all-caps title shows no case, so nothing counts as cut there.
     */
    private fun cutShort(
        act: String,
        title: String
    ): Boolean {
        // Only the act ignores case, so the next word still has to start with a capital.
        val continued = Regex("$NO_WORD_BEFORE(?iu:${Regex.escape(act)})$NO_WORD_AFTER(?:\\s+(\\p{Lu}\\p{Ll}[\\p{L}'-]*))?")
        val occurrences = continued.findAll(title).toList()
        return occurrences.isNotEmpty() &&
            occurrences.all { match ->
                val next = match.groups[1]?.value
                next != null && next.lowercase(Locale.ROOT) !in NAME_SUFFIXES
            }
    }
}
