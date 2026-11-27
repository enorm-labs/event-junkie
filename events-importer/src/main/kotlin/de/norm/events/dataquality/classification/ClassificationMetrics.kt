package de.norm.events.dataquality.classification

import de.norm.events.common.LastSuccess
import de.norm.events.common.LastSuccessGauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

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
) : LastSuccess by LastSuccessGauge(registry, LAST_SUCCESS) {
    init {
        registry.gauge(TOKEN_BUDGET, properties) { it.monthlyTokenBudget.toDouble() }
    }

    companion object {
        /** `importer_classification_last_success`, epoch seconds of the last ended [ClassificationRunner] pass. */
        const val LAST_SUCCESS = "importer.classification.last_success"

        /** `importer_classification_token_budget`, the rule's denominator against [ClassificationTokenLedger.TOKENS_GAUGE]. */
        const val TOKEN_BUDGET = "importer.classification.token_budget"
    }
}
