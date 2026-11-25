package de.norm.events.dataquality.flyer

import de.norm.events.dataquality.classification.ClassificationProperties
import de.norm.events.dataquality.classification.ClassificationTokenLedger
import de.norm.events.dataquality.classification.EventClassificationCheck
import de.norm.events.dataquality.classification.EventClassificationRepository
import de.norm.events.dataquality.classification.NewSuggestion
import de.norm.events.dataquality.classification.SuggestionInsert
import de.norm.events.image.ImgproxyClient
import de.norm.events.scraper.LogContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What one pass of [FlyerLineupCheck] did. [suggested] counts the rows it wrote, per field, and [rejections] the answers it turned down, per reason. */
data class FlyerPassResult(
    val candidates: Int,
    val read: Int,
    val rejected: Int,
    val failed: Int,
    val suggested: Map<String, Int>,
    val overBudget: Int = 0,
    val rejections: Map<FlyerRejection, Int> = emptyMap()
)

/**
 * The flyer-lineup check (#5048, ADR-059): one model call per stored flyer of a music night with no act, at the
 * sources [FlyerProperties.sources] names. It writes `artists`, `startTime` and `doorsTime` suggestions and never an event
 * (ADR-041 rule 3). A flyer read once is not read again until the event's image changes.
 */
@Service
@ConditionalOnProperty(prefix = "app.data-quality", name = ["classification.enabled", "flyer.enabled"], havingValue = "true")
@Suppress("LongParameterList") // Two repositories, the reader, the renderer, the shared budget and a meter.
class FlyerLineupCheck(
    private val repository: FlyerRepository,
    private val suggestions: EventClassificationRepository,
    private val reader: AnthropicFlyerReader,
    private val imgproxy: ImgproxyClient,
    private val ledger: ClassificationTokenLedger,
    private val properties: FlyerProperties,
    private val classification: ClassificationProperties,
    private val meterRegistry: MeterRegistry
) {
    private val logger = KotlinLogging.logger {}

    /** Reads up to [FlyerProperties.maxPerRun] flyers, soonest event first. The nightly classification pass calls it after its own. */
    suspend fun pass(): FlyerPassResult {
        val sources = properties.sources.filter { it.isNotBlank() }
        val targets = repository.findCandidates(sources, properties.maxPerRun)
        val tally = Tally(ledger.monthTotal())
        logger.info {
            "Flyer-lineup pass started: ${targets.size} flyer(s) of ${sources.size} source(s), " +
                "${tally.monthTokens} of ${ledger.budget} tokens used this month"
        }
        targets.forEach { target ->
            if (tally.monthTokens >= ledger.budget) tally.overBudget++ else withContext(LogContext.forEvent(target.id)) { read(target, tally) }
        }
        val result =
            FlyerPassResult(
                targets.size,
                tally.read,
                tally.rejections.values.sum(),
                tally.failed,
                tally.suggested.toSortedMap(),
                tally.overBudget,
                tally.rejections.toSortedMap()
            )
        logger.info {
            "Flyer-lineup pass finished: ${result.read} read, ${result.rejected} rejected ${result.rejections}, ${result.failed} failed, " +
                "${result.overBudget} over the token budget; suggested ${result.suggested}"
        }
        return result
    }

    private class Tally(
        var monthTokens: Long
    ) {
        var read = 0
        val rejections = mutableMapOf<FlyerRejection, Int>()
        var failed = 0
        var overBudget = 0
        val suggested = mutableMapOf<String, Int>()
    }

    private suspend fun read(
        target: FlyerTarget,
        tally: Tally
    ) {
        // imgproxy logs its own refusal; the event stays a candidate for the next night.
        val image = imgproxy.render(target.contentHash, properties.width, FORMAT)
        if (image == null) {
            tally.failed++
            outcome(OUTCOME_FAILED)
            return
        }
        val result = reader.read(FlyerInput(target.title, target.eventDate, image))
        if (result != FlyerReadResult.Failed) tally.monthTokens = ledger.record(result.usage)
        when (result) {
            is FlyerReadResult.Answered -> {
                tally.read++
                repository.recordRead(target.id, target.contentHash, reader.model)
                proposals(target, result.answer).forEach { insert(it, tally) }
                outcome(OUTCOME_ANSWERED)
            }

            is FlyerReadResult.Rejected -> {
                tally.rejections.merge(result.reason, 1, Int::plus)
                logger.info { "The flyer answer was rejected: ${result.reason}, ${result.usage.output} output tokens" }
                // A broken answer says nothing about the flyer, so the next pass asks again (#4758).
                if (result.reason != FlyerRejection.NOT_JSON) repository.recordRead(target.id, target.contentHash, reader.model)
                outcome(OUTCOME_REJECTED)
            }

            FlyerReadResult.Failed -> {
                tally.failed++
                outcome(OUTCOME_FAILED)
            }
        }
    }

    /** An `artists` row for acts above the artists floor, and a row for each time the event does not have. */
    private fun proposals(
        target: FlyerTarget,
        answer: FlyerAnswer
    ): List<NewSuggestion> =
        listOfNotNull(
            answer.acts?.takeIf { it.confidence >= classification.minConfidence.artists }?.let {
                NewSuggestion(target.id, FIELD_ARTISTS, null, it.value.joinToString(LIST_SEPARATOR), it.evidence, it.confidence, reader.model)
            },
            answer.startTime?.takeIf { target.startTime == null && it.confidence >= classification.minConfidence.startTime }?.let {
                NewSuggestion(target.id, FIELD_START_TIME, null, it.value.format(TIME_FORMAT), it.evidence, it.confidence, reader.model)
            },
            answer.doorsTime?.takeIf { target.doorsTime == null && it.confidence >= classification.minConfidence.startTime }?.let {
                NewSuggestion(target.id, FIELD_DOORS_TIME, null, it.value.format(TIME_FORMAT), it.evidence, it.confidence, reader.model)
            }
        )

    private suspend fun insert(
        suggestion: NewSuggestion,
        tally: Tally
    ) {
        if (suggestions.insertUnlessOpen(suggestion, CHECK_NAME) == SuggestionInsert.INSERTED) {
            tally.suggested.merge(suggestion.field, 1, Int::plus)
            meterRegistry.counter(EventClassificationCheck.SUGGESTIONS_METER, TAG_CHECK, CHECK_NAME, TAG_FIELD, suggestion.field).increment()
        }
    }

    private fun outcome(outcome: String) = meterRegistry.counter(READS_METER, TAG_OUTCOME, outcome).increment()

    companion object {
        const val CHECK_NAME = "flyer-lineup"

        /** `importer.data_quality.flyer_reads{outcome}`: answered, rejected or failed flyer reads. */
        private const val READS_METER = "importer.data_quality.flyer_reads"

        private const val FIELD_ARTISTS = EventClassificationCheck.FIELD_ARTISTS
        private const val FIELD_START_TIME = EventClassificationCheck.FIELD_START_TIME
        private const val FIELD_DOORS_TIME = EventClassificationCheck.FIELD_DOORS_TIME
        private const val FORMAT = "jpg"
        private const val LIST_SEPARATOR = "; "
        private const val TAG_CHECK = "check"
        private const val TAG_FIELD = "field"
        private const val TAG_OUTCOME = "outcome"
        private const val OUTCOME_ANSWERED = "answered"
        private const val OUTCOME_REJECTED = "rejected"
        private const val OUTCOME_FAILED = "failed"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}
