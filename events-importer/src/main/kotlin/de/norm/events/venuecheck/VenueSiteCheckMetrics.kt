package de.norm.events.venuecheck

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The site check's one meter (#3147). `ej-venue-site-check-stale` reads its age, because a failed pass otherwise
 * leaves only an ERROR line, and no alert rule reads a log.
 */
@Component
class VenueSiteCheckMetrics(
    registry: MeterRegistry
) {
    /** Epoch seconds of the last pass that finished; 0 before the first. */
    private val lastSuccess = AtomicLong(0)

    init {
        registry.gauge(LAST_SUCCESS, lastSuccess) { it.get().toDouble() }
    }

    /** Stamps a pass that finished at [at]. */
    fun markSucceeded(at: Instant) = lastSuccess.set(at.epochSecond)

    /**
     * Starts the gauge from a stored check at [at]. A monthly pass outlives many restarts, and a gauge at 0 after
     * each one hides the age until the next pass. A pass that finished first is kept.
     */
    fun seed(at: Instant) {
        lastSuccess.accumulateAndGet(at.epochSecond, ::maxOf)
    }

    companion object {
        /** `importer.venues.site_check.last_success`, epoch seconds of the last finished [VenueSiteCheckService] pass. */
        const val LAST_SUCCESS = "importer.venues.site_check.last_success"
    }
}
