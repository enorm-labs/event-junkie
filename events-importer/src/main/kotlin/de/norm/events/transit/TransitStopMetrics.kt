package de.norm.events.transit

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The station import's one meter (#4596). `ej-transit-stops-stale` reads its age, because a failed import otherwise
 * leaves only an ERROR line, and no alert rule reads a log.
 */
@Component
class TransitStopMetrics(
    registry: MeterRegistry
) {
    /** Epoch seconds of the last import that replaced the rows; 0 before the first. */
    private val lastSuccess = AtomicLong(0)

    init {
        registry.gauge(LAST_SUCCESS, lastSuccess) { it.get().toDouble() }
    }

    /** Stamps an import that replaced the rows at [at]. */
    fun markSucceeded(at: Instant) = lastSuccess.set(at.epochSecond)

    /**
     * Starts the gauge from the stored rows' import at [at]. A quarterly import outlives many restarts, and a gauge
     * at 0 after each one hides the age until the next import. An import that finished first is kept.
     */
    fun seed(at: Instant) {
        lastSuccess.accumulateAndGet(at.epochSecond, ::maxOf)
    }

    companion object {
        /** `importer.transit_stops.import.last_success`, epoch seconds of the last [TransitStopImportService] import. */
        const val LAST_SUCCESS = "importer.transit_stops.import.last_success"
    }
}
