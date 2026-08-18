package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Records what each run extracted, and says so when a source starts publishing less (#472).
 *
 * ### The failure this catches, and why nothing else catches it
 *
 * #415 alerts when a source imports **zero** events. That catches a scraper that broke completely.
 * The quieter and far more common failure is partial: a venue moves the price into a different
 * element, one selector stops matching, and the importer keeps running, reports success, and writes
 * the same forty events it always does — every one of them now missing a price. **Counts are
 * unchanged, so no count-based alert fires.** The data just gets worse, and it is found weeks later
 * by somebody reading the site.
 *
 * ### The baseline is derived from history, never from an expectation
 *
 * **This is the decision that makes the feature work rather than annoy.** The baseline for a field
 * is the **median** coverage ratio over the last [baselineRuns] runs of *that source*. Median rather
 * than max, because a max makes one lucky run the standard forever; and per source, because a venue
 * that has never published a price has a baseline of 0% for price and is never flagged for missing
 * one. A global "every event should have a price" expectation would flag half the corpus
 * permanently, and a dashboard that is permanently red stops being read.
 *
 * It also means a field that **starts** arriving raises its own baseline after enough runs, with no
 * code change and no list to maintain — the auto-widening the issue asks for comes free from
 * deriving rather than storing.
 *
 * ### Two guards, because without both this is a noise generator
 *
 * - **A large enough sample.** A run that scraped three events proves nothing. Below
 *   [minSampleSize] the run is recorded and never compared.
 * - **Persistence.** Flag on the *second* consecutive run below baseline, not the first. A single
 *   run can legitimately skew — a week of club nights has no lineup and no support act, and that is
 *   not a regression.
 *
 * Without both, this gets muted, which is strictly worse than not building it.
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
     * Records one run's coverage and evaluates every field against its baseline.
     *
     * Called after a successful scrape, from the events the scraper produced rather than from the
     * rows in the database. That distinction matters: this measures **what was extracted**, which is
     * what a broken selector affects, whereas the table also holds everything previous runs wrote.
     *
     * Returns the fields that flagged, which is what the caller logs and what the tests assert.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: measurement must not be able to fail an import
    suspend fun record(
        source: EventSourceEntity,
        events: Collection<ScrapedEvent>
    ): List<CoverageFinding> =
        try {
            measure(source, events)
        } catch (e: Exception) {
            // **A measurement that can fail an import is worse than no measurement**, and the
            // guarantee lives here rather than at the call site so every caller gets it without
            // remembering to. This runs after the upsert has already committed, so a throw would
            // report a failed run that wrote every event, consume retry budget, and back the source
            // off — the measurement costing exactly the thing it exists to observe. The visible
            // cost is a gap in one source's coverage history, which is itself detectable.
            logger.warn(e) { "Could not record field coverage for '${source.slug}'; its history will have a gap" }
            emptyList()
        }

    private suspend fun measure(
        source: EventSourceEntity,
        events: Collection<ScrapedEvent>
    ): List<CoverageFinding> {
        // An unpersisted source has no history to compare against, and zero events is #415's alarm
        // rather than this one — a row of all-zero coverage would drag every baseline down and make
        // the next real run look like the regression.
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
            // judging. Written after, so a run that throws mid-evaluation leaves no half-record.
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
     * The rule, in one place.
     *
     * `null` means "nothing to say" — either there is not enough history for a baseline, the sample
     * is too small to compare, or the coverage is fine.
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

            // Guard two: the previous run must have been below the SAME baseline. Comparing it
            // against that number rather than against its own history is deliberate — otherwise a
            // slow two-run slide would move the goalposts with it and never flag.
            !isMaterialDrop(previous.first(), baseline) -> null

            else -> CoverageFinding(field, observed = ratio, baseline = baseline, sampleSize = sampleSize)
        }
    }

    /**
     * **Both conditions, and the second is what stops this being noise.**
     *
     * Halving is the relative test, and it is the one that catches a selector breaking — coverage
     * goes to zero or near it. On its own it would fire on a field sitting at 8% that drifts to 3%,
     * which is a difference of five events in a hundred and almost certainly the venue rather than
     * us. The twenty-point floor is what excludes that, and it is why a genuinely low-coverage field
     * is effectively unflaggable — correctly, because there is no signal there to lose.
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
     * Sets or clears the flag on the source row.
     *
     * **Cleared when a run looks normal again**, which is not optional: a flag that only ever gets
     * set is a flag that is permanently on within a month, and then it is decoration. The clear is
     * what makes the set mean something.
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
        // WARN rather than INFO: this is the line that should reach a human. It names the source, the
        // field, the baseline and the observed ratio, so the log entry alone is enough to decide
        // whether to look at the venue's page.
        logger.warn { "Field coverage dropped for source '${source.slug}': $reason" }
    }

    companion object {
        private const val DEFAULT_BASELINE_RUNS = 10
        private const val DEFAULT_MIN_SAMPLE = 10

        /**
         * At least three prior runs before a baseline means anything. Two would make the median the
         * mean of two numbers, which is exactly the "one lucky run is the standard" failure the
         * median was chosen to avoid.
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
