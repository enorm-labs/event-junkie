package de.norm.events.image

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * What the image route did, and how much of the bucket this process is holding.
 *
 * **Two questions the Caffeine meters cannot answer**, which is why this exists beside them rather
 * than instead of them. `CaffeineCacheMetrics` publishes `cache_gets{cache="images",result}` — the
 * hit ratio — and `cache_size`, which is the **entry count**. Neither says whether a request that
 * reached the bucket found anything there, and neither reports the bytes held (#847, #880).
 *
 * Names and tags are an interface: `docs/ops/PLATFORM_SETUP.md` §7 and `deploy/alerts/gen_alerts.py`
 * are written against these strings and nothing checks that the three agree.
 */
@Component
class ImageServingMetrics(
    registry: MeterRegistry,
    cache: ImageObjectCache
) {
    /**
     * Every outcome, registered at construction rather than on first increment.
     *
     * **A counter that has never incremented is absent from the exposition, and an absent series
     * cannot fire a rule** — `deploy/alerts/README.md` records what that cost once already. `missing`
     * is the outcome a rule is written against, and it is by definition the one that has never
     * happened on a healthy origin. Registering at zero is what makes such a rule evaluable.
     */
    private val served = Outcome.entries.associateWith { registry.counter(SERVED, TAG_OUTCOME, it.tag) }

    init {
        registry.gauge(CACHE_WEIGHT, cache) { it.weightedBytes().toDouble() }
    }

    fun record(outcome: Outcome) = served.getValue(outcome).increment()

    /**
     * The four ways the route ends, kept apart because they mean different things about the system.
     *
     * `unknown` is a visitor or a crawler asking for something that never existed. The other three
     * are all "we promised this and here is what happened", which is the interesting half.
     */
    enum class Outcome(
        val tag: String
    ) {
        /** Bytes were returned, from the bucket or from memory. */
        FOUND("found"),

        /** The path was malformed, or no variant row names it. A 404 nobody should worry about. */
        UNKNOWN("unknown"),

        /**
         * **A row named an object the bucket does not have**, which is a defect rather than traffic.
         *
         * It is the shape of a sweep that deleted something it should have kept, and until now the
         * only trace was a warning in the log.
         */
        MISSING("missing"),

        /** The store could not be reached. A 503, never a cached 404. */
        UNAVAILABLE("unavailable")
    }

    companion object {
        /** `bff.images.served{outcome}`, beside `bff.events.served`. */
        const val SERVED = "bff.images.served"

        /**
         * `bff.images.cache.weight` — bytes held, which is what the ceiling is set in.
         *
         * **Not `_total`**: Prometheus reserves that suffix for counters and Micrometer strips it
         * silently, which is how `db.events.total` became `db_events` (PLATFORM_SETUP.md §7).
         */
        const val CACHE_WEIGHT = "bff.images.cache.weight"

        const val TAG_OUTCOME = "outcome"
    }
}
