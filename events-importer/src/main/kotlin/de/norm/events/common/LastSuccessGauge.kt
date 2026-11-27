package de.norm.events.common

import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** The age a `*-stale` alert reads: when a scheduled pass last finished. */
interface LastSuccess {
    /** Stamps a pass that finished at [at]. */
    fun markSucceeded(at: Instant)

    /**
     * Starts the age at [at] after a restart, so the gauge does not read 0 until the next pass. A pass that finished
     * first is kept.
     */
    fun seed(at: Instant)
}

/**
 * A [LastSuccess] exported as the gauge [name], in epoch seconds, 0 before the first pass or seed. A metrics class
 * delegates to it and keeps [name] as its own constant, because the alert rules and the tests name the meter.
 */
class LastSuccessGauge(
    registry: MeterRegistry,
    name: String
) : LastSuccess {
    // Held here because the registry keeps a gauge's object weakly, and a collected one reads 0.
    private val epochSeconds = AtomicLong(0)

    init {
        registry.gauge(name, epochSeconds) { it.get().toDouble() }
    }

    override fun markSucceeded(at: Instant) = epochSeconds.set(at.epochSecond)

    override fun seed(at: Instant) {
        epochSeconds.accumulateAndGet(at.epochSecond, ::maxOf)
    }
}
