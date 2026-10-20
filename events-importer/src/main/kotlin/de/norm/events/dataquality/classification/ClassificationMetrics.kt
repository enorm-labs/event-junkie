package de.norm.events.dataquality.classification

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The nightly pass's age and the month's budget, for `ej-classification-stale` and `ej-classification-budget` (#3645).
 *
 * Not conditional, unlike the check: OpenObserve refuses an alert on a missing stream, and staging runs without the
 * check. There the age stays 0, which the rule reads as off.
 */
@Component
class ClassificationMetrics(
    registry: MeterRegistry,
    properties: ClassificationProperties
) {
    /** Epoch seconds of the last pass that ended; 0 while the check is off. */
    private val lastSuccess = AtomicLong(0)

    init {
        registry.gauge(LAST_SUCCESS, lastSuccess) { it.get().toDouble() }
        registry.gauge(TOKEN_BUDGET, properties) { it.monthlyTokenBudget.toDouble() }
    }

    /** Stamps a pass that ended at [at]. */
    fun markSucceeded(at: Instant) = lastSuccess.set(at.epochSecond)

    /**
     * Starts the age at [at], when the runner starts. A pass that a release broke then still ages and fires; at 0 the
     * rule would stay quiet until a pass succeeded. A pass that ended first is kept.
     */
    fun seed(at: Instant) {
        lastSuccess.accumulateAndGet(at.epochSecond, ::maxOf)
    }

    companion object {
        /** `importer_classification_last_success`, epoch seconds of the last ended [ClassificationRunner] pass. */
        const val LAST_SUCCESS = "importer.classification.last_success"

        /** `importer_classification_token_budget`, the rule's denominator against [ClassificationTokenLedger.TOKENS_GAUGE]. */
        const val TOKEN_BUDGET = "importer.classification.token_budget"
    }
}
