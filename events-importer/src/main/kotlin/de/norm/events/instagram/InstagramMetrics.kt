package de.norm.events.instagram

import de.norm.events.common.LastSuccess
import de.norm.events.common.LastSuccessGauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Counters of the daily Instagram pass (ADR-048), and its age. The names are an interface: `ej-instagram-access-refused`
 * in `deploy/alerts/` reads `instagram_graph_errors_total{kind="refused"}`, and `ej-instagram-stale` reads
 * `instagram_pass_last_success` (#3645).
 */
@Component
class InstagramMetrics(
    private val registry: MeterRegistry
) : LastSuccess by LastSuccessGauge(registry, LAST_SUCCESS) {
    // The alert's stream exists only once the series has been exported, and OpenObserve refuses an alert on a missing one.
    init {
        registry.counter(GRAPH_ERRORS, TAG_KIND, KIND_REFUSED)
    }

    /** Posts Business Discovery returned. */
    fun postsRead(count: Int) = registry.counter(POSTS_READ).increment(count.toDouble())

    /** Pending drafts stored. */
    fun drafts(count: Int) = registry.counter(DRAFTS).increment(count.toDouble())

    /** Model calls that gave no usable answer, by outcome: `rejected` or `failed`. */
    fun extraction(
        outcome: String,
        count: Int
    ) = registry.counter(EXTRACTIONS, TAG_OUTCOME, outcome).increment(count.toDouble())

    /** A venue Business Discovery could not read, by kind: `unreadable`, `refused`, `rate_limited` or `unavailable`. */
    fun graphError(kind: String) = registry.counter(GRAPH_ERRORS, TAG_KIND, kind).increment()

    companion object {
        private const val POSTS_READ = "instagram.posts.read"
        private const val DRAFTS = "instagram.drafts"
        private const val EXTRACTIONS = "instagram.extractions"
        private const val GRAPH_ERRORS = "instagram.graph.errors"

        /** `instagram_pass_last_success`, epoch seconds of the last ended [InstagramDraftRunner] pass. */
        const val LAST_SUCCESS = "instagram.pass.last_success"
        const val TAG_OUTCOME = "outcome"
        const val TAG_KIND = "kind"
        const val KIND_REFUSED = "refused"
    }
}
