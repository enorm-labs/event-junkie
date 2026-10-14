package de.norm.events.dataquality.classification

import de.norm.events.event.EventNotFoundException
import de.norm.events.event.PartyFeature
import de.norm.events.importing.EventFeatureRepository
import de.norm.events.importing.EventQualityFlagRepository
import de.norm.events.importing.EventSourceNotFoundException
import de.norm.events.importing.EventSourceRepository
import de.norm.events.importing.QualityFlagKind
import de.norm.events.scraper.LogContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** What one run of [EventClassificationCheck] did. [suggested] counts the rows it wrote, per field. */
data class ClassificationRunResult(
    val candidates: Int,
    val checked: Int,
    val unchanged: Int,
    val failed: Int,
    val overCap: Int,
    val suggested: Map<String, Int>
)

/**
 * The event-classification check (#3241): MusicBrainz first, then one model call per event, and one
 * `event_suggestion` row per field whose proposal differs from the stored value.
 *
 * It suggests only and never writes to `event` (ADR-041 rule 3). An event whose title, subtitle and
 * description are unchanged since its last answered check is skipped, so a dismissed suggestion stays dismissed.
 */
@Service
@ConditionalOnProperty(name = ["app.data-quality.classification.enabled"], havingValue = "true")
@Suppress("LongParameterList") // The check reads four tables through their owners' repositories and writes one meter.
class EventClassificationCheck(
    private val repository: EventClassificationRepository,
    private val classifier: AnthropicEventClassifier,
    private val sourceRepository: EventSourceRepository,
    private val featureRepository: EventFeatureRepository,
    private val flagRepository: EventQualityFlagRepository,
    private val properties: ClassificationProperties,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}

    /** Checks one event, whether or not it is a candidate. [force] sends an unchanged text again. */
    suspend fun checkEvent(
        eventId: Long,
        force: Boolean = false
    ): ClassificationRunResult {
        repository.findTarget(eventId) ?: throw EventNotFoundException(eventId)
        return run("event $eventId", listOf(eventId), force)
    }

    /** Checks the upcoming candidates of one source, soonest first, up to `max-per-run` model calls. */
    suspend fun checkSource(
        slug: String,
        force: Boolean = false
    ): ClassificationRunResult {
        sourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        return run("source '$slug'", repository.findSourceCandidateIds(slug), force)
    }

    private suspend fun run(
        scope: String,
        eventIds: List<Long>,
        force: Boolean
    ): ClassificationRunResult {
        logger.info { "Event classification of $scope started: ${eventIds.size} candidate(s), at most ${properties.maxPerRun} model call(s)" }
        val tally = Tally()
        runCatching { eventIds.forEach { step(it, force, tally) } }
            .onFailure { e ->
                if (e !is CancellationException) logger.error(e) { "Event classification of $scope failed after ${tally.calls} model call(s)" }
            }.getOrThrow()
        val result = ClassificationRunResult(eventIds.size, tally.calls, tally.unchanged, tally.failed, tally.overCap, tally.suggested.toSortedMap())
        logger.info {
            "Event classification of $scope finished: ${result.checked} checked, ${result.unchanged} unchanged, ${result.failed} failed, " +
                "${result.overCap} over the cap; suggested ${result.suggested}"
        }
        return result
    }

    private suspend fun step(
        eventId: Long,
        force: Boolean,
        tally: Tally
    ) {
        if (tally.calls >= properties.maxPerRun) {
            tally.overCap++
            return
        }
        val target = repository.findTarget(eventId) ?: return
        if (!force && target.checkedHash == target.contentHash) {
            tally.unchanged++
            outcome(OUTCOME_UNCHANGED)
        } else {
            classify(target, tally)
        }
    }

    private class Tally {
        var calls = 0
        var unchanged = 0
        var failed = 0
        var overCap = 0
        val suggested = mutableMapOf<String, Int>()
    }

    private suspend fun classify(
        target: ClassificationTarget,
        tally: Tally
    ) {
        val lineup = repository.findLineup(target.id)
        val features = featureRepository.findByEventIds(listOf(target.id)).map { it.feature }
        val cues =
            flagRepository
                .findByEventIds(listOf(target.id))
                .filter { it.kind == QualityFlagKind.UNCERTAIN_PARTY_FEATURE }
                .mapNotNull { parseCue(it.value) }
        val musicBrainzActs = musicBrainzActs(target.title, lineup)
        val suggestions = mutableListOf<NewSuggestion>()
        if (musicBrainzActs.isNotEmpty()) {
            suggestions += artistsSuggestion(target, lineup, lineup.map { it.name } + musicBrainzActs, target.title, MUSICBRAINZ_CONFIDENCE, model = null)
        }

        val input = ClassificationInput(target.title, target.subtitle, target.description, cues)
        tally.calls++
        when (val result = withContext(LogContext.forEvent(target.id)) { classifier.classify(input) }) {
            is ClassifierResult.Answered -> {
                suggestions += Proposals(target, lineup, musicBrainzActs, features, result.answer, classifier.model).all()
                repository.recordChecked(target.id, target.contentHash, classifier.model)
                outcome(OUTCOME_ANSWERED)
            }

            ClassifierResult.Rejected -> {
                repository.recordChecked(target.id, target.contentHash, classifier.model)
                outcome(OUTCOME_REJECTED)
            }

            ClassifierResult.Failed -> {
                tally.failed++
                outcome(OUTCOME_FAILED)
            }
        }
        suggestions.filter { repository.insertUnlessOpen(it) }.forEach { suggestion ->
            tally.suggested.merge(suggestion.field, 1, Int::plus)
            meterRegistry.counter(SUGGESTIONS_METER, TAG_CHECK, CHECK_NAME, TAG_FIELD, suggestion.field).increment()
        }
    }

    private fun outcome(outcome: String) = meterRegistry.counter(CLASSIFICATIONS_METER, TAG_OUTCOME, outcome).increment()

    /**
     * Title segments that name an `EXACT` MusicBrainz artist and are missing from the lineup (ADR-031).
     * A title of one segment goes to the model instead: "ELVIS" matches and is a production.
     */
    private suspend fun musicBrainzActs(
        title: String,
        lineup: List<LineupEntry>
    ): List<String> {
        val segments = title.split(SEGMENT_SEPARATOR).map { it.trim() }.filter { it.length >= MIN_SEGMENT_LENGTH }
        if (segments.size < 2) return emptyList()
        val billed = lineup.map { it.name.lowercase() }.toSet()
        val exact = repository.findExactArtists(segments).associateBy { it.lowercase() }
        return segments.mapNotNull { exact[it.lowercase()] }.filter { it.lowercase() !in billed }.distinct()
    }

    /** The proposals of one model answer, one per field that differs from the stored value. */
    private class Proposals(
        private val target: ClassificationTarget,
        private val lineup: List<LineupEntry>,
        private val musicBrainzActs: List<String>,
        private val features: List<String>,
        private val answer: ClassificationAnswer,
        private val model: String
    ) {
        fun all(): List<NewSuggestion> = listOfNotNull(eventType(), artists(), features(), startTime(), pricePresale(), priceBoxOffice())

        private fun eventType() =
            answer.eventType?.confident()?.takeIf { it.value.name != target.eventType }?.let {
                NewSuggestion(target.id, FIELD_EVENT_TYPE, target.eventType, it.value.name, it.evidence, it.confidence, model)
            }

        /** Adds the acts the title names, and drops a title-derived artist the model does not call an act. */
        private fun artists(): NewSuggestion? {
            val acts = answer.titleActs?.confident() ?: return null
            val current = lineup.map { it.name } + musicBrainzActs
            val named = acts.value.map { it.lowercase() }.toSet()
            val kept = lineup.filter { !it.titleDerived || it.name.lowercase() in named }.map { it.name } + musicBrainzActs
            val added = acts.value.filter { act -> current.none { it.equals(act, ignoreCase = true) } }
            val proposed = (kept + added).distinctBy { it.lowercase() }
            return if (proposed == current) null else artistsSuggestion(target, lineup, proposed, acts.evidence, acts.confidence, model)
        }

        private fun features(): NewSuggestion? {
            val confirmed = answer.features.filter { it.confirmed && it.confidence >= MIN_CONFIDENCE }
            val proposed = (features + confirmed.map { it.feature.slug }).toSortedSet()
            if (proposed == features.toSortedSet()) return null
            return NewSuggestion(
                target.id,
                FIELD_FEATURES,
                features.sorted().joinToString(LIST_SEPARATOR).ifEmpty { null },
                proposed.joinToString(LIST_SEPARATOR),
                confirmed.joinToString(" | ") { it.evidence },
                confirmed.minOf { it.confidence },
                model
            )
        }

        private fun startTime() =
            answer.startTime?.confident()?.let { found ->
                val proposed = found.value.format(TIME_FORMAT)
                val stored = target.startTime?.format(TIME_FORMAT)
                if (proposed == stored) null else NewSuggestion(target.id, FIELD_START_TIME, stored, proposed, found.evidence, found.confidence, model)
            }

        private fun pricePresale() = price(FIELD_PRICE_PRESALE, answer.pricePresale, target.pricePresale)

        private fun priceBoxOffice() = price(FIELD_PRICE_BOX_OFFICE, answer.priceBoxOffice, target.priceBoxOffice)

        private fun price(
            field: String,
            finding: Finding<BigDecimal>?,
            stored: BigDecimal?
        ) = finding?.confident()?.let { found ->
            val proposed = found.value.toPlainString()
            val storedText = stored?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
            if (proposed == storedText) null else NewSuggestion(target.id, field, storedText, proposed, found.evidence, found.confidence, model)
        }

        private fun <T> Finding<T>.confident(): Finding<T>? = takeIf { it.confidence >= MIN_CONFIDENCE }
    }

    companion object {
        const val CHECK_NAME = "event-classification"

        const val FIELD_EVENT_TYPE = "eventType"
        const val FIELD_ARTISTS = "artists"
        const val FIELD_FEATURES = "features"
        const val FIELD_START_TIME = "startTime"
        const val FIELD_PRICE_PRESALE = "pricePresale"
        const val FIELD_PRICE_BOX_OFFICE = "priceBoxOffice"

        /** `importer.data_quality.suggestions{check,field}`: suggestion rows written. */
        const val SUGGESTIONS_METER = "importer.data_quality.suggestions"

        /** `importer.data_quality.classifications{outcome}`: answered, rejected, failed or unchanged events. */
        const val CLASSIFICATIONS_METER = "importer.data_quality.classifications"

        private const val TAG_CHECK = "check"
        private const val TAG_FIELD = "field"
        private const val TAG_OUTCOME = "outcome"
        private const val OUTCOME_ANSWERED = "answered"
        private const val OUTCOME_REJECTED = "rejected"
        private const val OUTCOME_FAILED = "failed"
        private const val OUTCOME_UNCHANGED = "unchanged"

        /** Below this the answer is a guess, and a guess is the busywork #474 warns about. #2997 measures it. */
        const val MIN_CONFIDENCE = 0.7

        /** An `EXACT` artist as a part of a longer title: strong, and still a proposal. */
        private const val MUSICBRAINZ_CONFIDENCE = 0.9

        private const val MIN_SEGMENT_LENGTH = 2
        private const val LIST_SEPARATOR = "; "
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        /** Separators that join billed names in a title. `&` and `und` stay out: they join the names of one act as often. */
        private val SEGMENT_SEPARATOR = Regex("\\s+[-–—|+/]\\s+|,\\s+|:\\s+|\\s+(?:feat\\.?|ft\\.|w/|b2b|vs\\.?)\\s+", RegexOption.IGNORE_CASE)

        /** `flinta-only: FLINTA* Party` → the feature and its cue, as `EventFeatureSync` writes the flag. */
        fun parseCue(value: String): UncertainCue? {
            val feature = PartyFeature.fromSlug(value.substringBefore(':').trim()) ?: return null
            return UncertainCue(feature, value.substringAfter(':').trim())
        }

        private fun artistsSuggestion(
            target: ClassificationTarget,
            lineup: List<LineupEntry>,
            proposed: List<String>,
            evidence: String,
            confidence: Double,
            model: String?
        ) = NewSuggestion(
            target.id,
            FIELD_ARTISTS,
            lineup.joinToString(LIST_SEPARATOR) { it.name }.ifEmpty { null },
            proposed.joinToString(LIST_SEPARATOR),
            evidence,
            confidence,
            model
        )
    }
}
