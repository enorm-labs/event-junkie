package de.norm.events.dataquality

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The same measurements as Prometheus gauges, so a dashboard and an alert can read them.
 *
 * **Gauges are refreshed on a schedule and never queried from inside a supplier**, for the reason
 * `ImporterMetrics` states at length: Micrometer reads a gauge synchronously, on the thread serving
 * `/actuator/prometheus`, and every query this application can make suspends. A supplier that asked
 * the database would block a Netty event-loop thread — the one thing ADR-001 rules out everywhere.
 *
 * So each gauge reads an [AtomicLong] that [DataQualityReportLogger] refreshes. The cost is that a
 * gauge is as stale as that schedule, which for a number that moves on an import cycle measured in
 * hours is irrelevant.
 *
 * **Two tags, `source` and `metric`, rather than one meter per metric.** They are the same
 * measurement over different fields, which is what a label is for, and it means adding a metric to
 * [QualityIssue] needs no change here at all.
 *
 * Cardinality is bounded by the number of sources times the number of metrics — tens, not thousands
 * — and both sides are ours rather than a caller's, which is the property that makes tagging safe.
 */
@Component
class DataQualityMetrics(
    private val registry: MeterRegistry
) {
    private val gauges = ConcurrentHashMap<Pair<String, String>, AtomicLong>()

    /**
     * Publishes one measurement, registering the series on first use.
     *
     * Sources appear at runtime — they are created through the admin API, not through Flyway — so
     * the set of series cannot be known at startup.
     */
    fun publish(
        source: String,
        metric: String,
        value: Long
    ) {
        gauges
            .computeIfAbsent(source to metric) { (s, m) ->
                val holder = AtomicLong(0)
                registry.gauge(GAUGE, Tags.of(TAG_SOURCE, s, TAG_METRIC, m), holder) { it.get().toDouble() }
                holder
            }.set(value)
    }

    companion object {
        /**
         * `data_quality{source="badehaus",metric="concertsWithoutArtist"}`.
         *
         * Deliberately not `data.quality.total` or anything ending in `_total`: Prometheus reserves
         * that suffix for counters and Micrometer silently strips it, which is how `db.events.total`
         * became `db_events` and broke every rule written against the documented name (§7).
         */
        const val GAUGE = "data.quality"
        const val TAG_SOURCE = "source"
        const val TAG_METRIC = "metric"

        /** The denominator, published alongside the metrics so a rule can express a ratio. */
        const val TOTAL_EVENTS = "totalEvents"
    }
}
