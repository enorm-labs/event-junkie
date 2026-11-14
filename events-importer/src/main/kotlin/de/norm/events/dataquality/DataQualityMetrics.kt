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
    private val unexplained = ConcurrentHashMap<Pair<String, String>, AtomicLong>()

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
    ) = holder(gauges, GAUGE, source, metric).set(value)

    /**
     * Publishes one `data_quality_unexplained{source,metric}` measurement (#5002): the count no accepted
     * limitation of the source explains, so 0 when one excuses the metric. The same refresh as [publish].
     */
    fun publishUnexplained(
        source: String,
        metric: String,
        value: Long
    ) = holder(unexplained, UNEXPLAINED_GAUGE, source, metric).set(value)

    private fun holder(
        series: ConcurrentHashMap<Pair<String, String>, AtomicLong>,
        name: String,
        source: String,
        metric: String
    ): AtomicLong =
        series.computeIfAbsent(source to metric) { (s, m) ->
            val holder = AtomicLong(0)
            registry.gauge(name, Tags.of(TAG_SOURCE, s, TAG_METRIC, m), holder) { it.get().toDouble() }
            holder
        }

    private val regressions = ConcurrentHashMap<Pair<String, String>, AtomicLong>()

    /**
     * Sets `data_quality_regression{source,metric}` to 1 for each regressed verdict and to 0 for every
     * other one (#2604).
     *
     * A series that today's check did not judge — a source that was deleted, a metric that was
     * retired — goes back to 0 first. Otherwise yesterday's 1 would stay in the process until the
     * next restart, and the alert would keep naming a source that is gone.
     */
    fun publishRegressions(verdicts: List<DataQualityRegressionCheck.Verdict>) {
        regressions.values.forEach { it.set(0) }
        verdicts.forEach { verdict ->
            holder(regressions, REGRESSION_GAUGE, verdict.source, verdict.metric).set(if (verdict.regressed) 1 else 0)
        }
    }

    /** The sources with at least one series of `data_quality_regression` at 1. The nightly classification pass takes them first. */
    fun regressedSources(): Set<String> = regressions.filterValues { it.get() > 0 }.keys.mapTo(sortedSetOf()) { it.first }

    companion object {
        /**
         * `data_quality{source="badehaus",metric="concertsWithoutArtist"}`.
         *
         * Deliberately not `data.quality.total` or anything ending in `_total`: Prometheus reserves
         * that suffix for counters and Micrometer silently strips it, which is how `db.events.total`
         * became `db_events` and broke every rule written against the documented name (§7).
         */
        const val GAUGE = "data.quality"

        /**
         * `data_quality_unexplained{source,metric}`: the `data_quality` count of the six metrics an
         * accepted limitation can excuse, or 0 when the source's declarations excuse it (#5002).
         */
        const val UNEXPLAINED_GAUGE = "data.quality.unexplained"

        /**
         * `data_quality_regression{source,metric}`: 1 while today's share is more than ten points
         * worse than the source's 7-day median, else 0. `ej-data-quality-regression` reads it.
         */
        const val REGRESSION_GAUGE = "data.quality.regression"
        const val TAG_SOURCE = "source"
        const val TAG_METRIC = "metric"

        /** The denominator, published alongside the metrics so a rule can express a ratio. */
        const val TOTAL_EVENTS = "totalEvents"
    }
}
