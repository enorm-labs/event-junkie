package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Records what each run extracted, and says so when a source starts publishing less (#472). #415
 * catches a scraper that broke completely; the common failure is partial: one selector stops
 * matching, the importer writes the same forty events, every one now missing a price, and no
 * count-based alert fires.
 *
 * The baseline is the median coverage ratio over the last [baselineRuns] runs of that source,
 * never an expectation: median so one lucky run is not the standard, per source so a venue that
 * never publishes a price has a baseline of 0% and is never flagged. A field that starts
 * arriving raises its own baseline. Two guards, or this is a noise generator: a sample of at
 * least [minSampleSize], and persistence, flagging on the second consecutive run below baseline,
 * since a week of club nights legitimately has no lineup.
 */
@Service
class FieldCoverageService(
    private val stats: ImportRunFieldStatsRepository,
    private val eventSourceRepository: EventSourceRepository,
    private val metrics: ImporterMetrics,
    @Value($$"${app.field-coverage.baseline-runs:10}") private val baselineRuns: Int = DEFAULT_BASELINE_RUNS,
    @Value($$"${app.field-coverage.min-sample-size:10}") private val minSampleSize: Int = DEFAULT_MIN_SAMPLE,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Records one run's coverage and evaluates every field against its baseline, from the events the
     * scraper produced rather than the rows in the database, which also hold what earlier runs
     * wrote. Returns the fields that flagged.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: measurement must not be able to fail an import
    suspend fun record(
        source: EventSourceEntity,
        events: Collection<ScrapedEvent>
    ): List<CoverageFinding> =
        try {
            measure(source, events)
        } catch (e: Exception) {
            // A measurement that can fail an import is worse than no measurement, guaranteed here so every
            // caller gets it. This runs after the upsert committed, so a throw would report a failed run
            // that wrote every event and back the source off. The visible cost is a gap in one source's
            // history, itself detectable.
            logger.warn(e) { "Could not record field coverage for '${source.slug}'; its history will have a gap" }
            emptyList()
        }

    private suspend fun measure(
        source: EventSourceEntity,
        events: Collection<ScrapedEvent>
    ): List<CoverageFinding> {
        // An unpersisted source has no history, and zero events is #415's alarm: a row of all-zero
        // coverage would drag every baseline down.
        val sourceId = source.id
        if (sourceId == null || events.isEmpty()) return emptyList()

        val runId = UUID.randomUUID()
        val observedAt = Instant.now(clock)
        val findings = mutableListOf<CoverageFinding>()

        for (field in TrackedField.entries) {
            val withValue = field.countIn(events)
            val ratio = withValue.toDouble() / events.size
            metrics.publishFieldCoverage(source.slug, field.key, ratio)

            // History BEFORE this run's row is written, so the baseline never includes the run it is
            // judging; written after, so a run that throws leaves no half-record.
            val history = stats.findRecent(sourceId, field.key, baselineRuns + 1).toList()
            stats.save(
                ImportRunFieldStatsEntity(
                    runId = runId,
                    sourceId = sourceId,
                    field = field.key,
                    eventsTotal = events.size,
                    eventsWithValue = withValue,
                    observedAt = observedAt
                )
            )

            evaluate(field, ratio, events.size, history)?.let(findings::add)
        }

        applyFlag(source, findings)
        return findings
    }

    /**
     * The rule, in one place. `null` means nothing to say: not enough history, too small a sample,
     * or fine.
     */
    private fun evaluate(
        field: TrackedField,
        ratio: Double,
        sampleSize: Int,
        history: List<ImportRunFieldStatsEntity>
    ): CoverageFinding? {
        val previous = history.take(baselineRuns).map { it.eventsWithValue.toDouble() / it.eventsTotal }
        val baseline = if (previous.size >= MIN_BASELINE_RUNS) median(previous) else null

        return when {
            // Guard one: a run that scraped three events proves nothing about coverage.
            sampleSize < minSampleSize -> null

            // Not enough history for a median to mean anything.
            baseline == null -> null

            !isMaterialDrop(ratio, baseline) -> null

            // Guard two: the previous run must have been below the SAME baseline, or a slow two-run slide
            // would move the goalposts with it.
            !isMaterialDrop(previous.first(), baseline) -> null

            else -> CoverageFinding(field, observed = ratio, baseline = baseline, sampleSize = sampleSize)
        }
    }

    /**
     * Both conditions. Halving catches a selector breaking; on its own it would fire on a field at
     * 8% drifting to 3%, five events in a hundred and almost certainly the venue. The twenty-point
     * floor excludes that, so a genuinely low-coverage field is effectively unflaggable, correctly.
     */
    private fun isMaterialDrop(
        observed: Double,
        baseline: Double
    ): Boolean = observed <= baseline * RELATIVE_DROP && baseline - observed >= ABSOLUTE_DROP

    /** True median, averaging the middle pair on an even count. */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /**
     * Sets or clears the flag on the source row. Cleared when a run looks normal again, or the flag
     * is permanently on within a month.
     */
    private suspend fun applyFlag(
        source: EventSourceEntity,
        findings: List<CoverageFinding>
    ) {
        val id = source.id ?: return
        if (findings.isEmpty()) {
            if (source.flaggedAt != null) {
                eventSourceRepository.clearFlag(id)
                logger.info { "Source '${source.slug}' field coverage is back to normal; flag cleared" }
            }
            return
        }

        val reason = findings.joinToString("; ") { it.describe() }
        eventSourceRepository.setFlag(id, Instant.now(clock), reason.take(MAX_REASON_LENGTH))
        // WARN: the line that should reach a human, naming source, field, baseline and observed ratio.
        logger.warn { "Field coverage dropped for source '${source.slug}': $reason" }
    }

    companion object {
        private const val DEFAULT_BASELINE_RUNS = 10
        private const val DEFAULT_MIN_SAMPLE = 10

        /**
         * At least three prior runs; two would make the median the mean of two numbers, the "one lucky
         * run" failure the median avoids.
         */
        const val MIN_BASELINE_RUNS = 3

        /** Coverage must be at or below half the baseline. */
        const val RELATIVE_DROP = 0.5

        /** ...and the drop must be at least twenty percentage points. */
        const val ABSOLUTE_DROP = 0.2

        private const val MAX_REASON_LENGTH = 500
    }
}

/** One field that dropped, in the shape the log line and the flag reason both need. */
data class CoverageFinding(
    val field: TrackedField,
    val observed: Double,
    val baseline: Double,
    val sampleSize: Int
) {
    /** `genre 5% of 40 events, baseline 98%` — everything needed to judge it without another query. */
    fun describe(): String = "${field.key} ${asPercent(observed)} of $sampleSize events, baseline ${asPercent(baseline)}"

    private fun asPercent(value: Double): String = "${Math.round(value * PERCENT)}%"

    private companion object {
        const val PERCENT = 100
    }
}
