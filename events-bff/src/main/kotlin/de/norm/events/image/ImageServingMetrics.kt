package de.norm.events.image

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * What the image route did, and how much of the bucket this process holds. Two questions the
 * Caffeine meters cannot answer: `cache_gets{cache="images",result}` is the hit ratio and
 * `cache_size` the entry count, and neither says whether a request that reached the bucket found
 * anything, nor the bytes held (#847, #880). Names and tags are an interface:
 * `docs/ops/PLATFORM_SETUP.md` §7 and `deploy/alerts/gen_alerts.py` are written against them.
 */
@Component
class ImageServingMetrics(
    registry: MeterRegistry,
    cache: ImageObjectCache
) {
    /**
     * Every outcome, registered at construction: a counter that has never incremented is absent
     * from the exposition, and an absent series cannot fire a rule (`deploy/alerts/README.md`).
     * `missing` is the one a rule is written against, and the one that never happens on a healthy
     * origin.
     */
    private val served = Outcome.entries.associateWith { registry.counter(SERVED, TAG_OUTCOME, it.tag) }

    init {
        registry.gauge(CACHE_WEIGHT, cache) { it.weightedBytes().toDouble() }
    }

    fun record(outcome: Outcome) = served.getValue(outcome).increment()

    /**
     * The four ways the route ends. `unknown` is a visitor or crawler asking for something that
     * never existed; the other three are "we promised this and here is what happened".
     */
    enum class Outcome(
        val tag: String
    ) {
        /** Bytes were returned, from the bucket or from memory. */
        FOUND("found"),

        /** The path was malformed, or no variant row names it. A 404 nobody should worry about. */
        UNKNOWN("unknown"),

        /**
         * A row named an object the bucket does not have: a defect, the shape of a sweep that deleted
         * something it should have kept.
         */
        MISSING("missing"),

        /** The store could not be reached. A 503, never a cached 404. */
        UNAVAILABLE("unavailable")
    }

    companion object {
        /** `bff.images.served{outcome}`, beside `bff.events.served`. */
        const val SERVED = "bff.images.served"

        /**
         * `bff.images.cache.weight`, bytes held. Not `_total`: Prometheus reserves the suffix for
         * counters and Micrometer strips it silently, how `db.events.total` became `db_events`
         * (PLATFORM_SETUP.md §7).
         */
        const val CACHE_WEIGHT = "bff.images.cache.weight"

        const val TAG_OUTCOME = "outcome"
    }
}
