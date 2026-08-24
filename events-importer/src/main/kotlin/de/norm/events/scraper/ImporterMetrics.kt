package de.norm.events.scraper

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * Every meter the importer publishes, and the only place their names and tags are written down.
 *
 * **A scraper does not fail loudly, and that is the whole reason this exists.** When a venue
 * redesigns its site the importer keeps running, reports success, and silently writes zero events —
 * and nobody notices for a fortnight, because the site still shows last month's listings. No amount
 * of HTTP or infrastructure monitoring sees that; a business metric with an alert does. ADR-015 calls
 * this the requirement the whole observability decision turns on.
 *
 * Names and tags follow `docs/ops/PLATFORM_SETUP.md` §7, which the dashboards and alert rules are
 * written against. **Renaming a meter here silently breaks an alert elsewhere** — there is no
 * compiler between the two — so treat these strings as an interface, not as an implementation
 * detail.
 *
 * **Counters are recorded here; gauges are refreshed elsewhere.** A counter is recorded at the
 * moment something happens, which is a normal method call. A gauge is
 * read by Micrometer *at scrape time*, synchronously, on whichever thread the actuator endpoint is
 * being served on — and every query this application can make is reactive and suspending. There is
 * no way to answer "how many events are in the database" from inside a gauge supplier without
 * blocking a Netty event-loop thread.
 *
 * So each gauge reads an [AtomicLong] that [MetricsRefreshService] updates on a schedule. The cost
 * is that a gauge is as stale as the refresh interval; the alternative is a blocking call in the
 * request path of a WebFlux application, which is the one thing ADR-001 rules out everywhere else.
 */
@Component
class ImporterMetrics(
    private val registry: MeterRegistry
) {
    /**
     * Timers, one per source, created on first use.
     *
     * Micrometer de-duplicates by name and tags, so re-deriving a `Timer` on every run would be
     * correct — but it is a map lookup plus tag construction on a hot-ish path, and caching makes
     * the intent ("one timer per source") legible.
     */
    private val runTimers = ConcurrentHashMap<String, Timer>()

    /**
     * Epoch-second of each source's last **successful** run, backing the `last_success` gauge.
     *
     * A `ConcurrentHashMap` rather than a field, because the set of sources changes at runtime:
     * sources are created through the admin API, not through Flyway.
     */
    private val lastSuccessEpochSeconds = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Whether each source has *ever* succeeded, backing the `has_succeeded` gauge — 1 or 0.
     *
     * Separate from [lastSuccessEpochSeconds] rather than derived from it, because the whole point is
     * that this map has an entry for sources the other one does not.
     */
    private val hasSucceeded = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Future events currently held per source, backing the `events_future` gauge (#700).
     *
     * Keyed by slug like the two above, and for the same reason: sources are created through the
     * admin API at runtime, so the set is not known at construction time.
     */
    private val futureEvents = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Per-source, per-field coverage ratios. A `Double` holder rather than an [AtomicLong], because
     * this is the one meter here that is a fraction and rounding it to a long would make every value
     * either 0 or 1.
     */
    private val fieldCoverage = ConcurrentHashMap<Pair<String, String>, java.util.concurrent.atomic.AtomicReference<Double>>()

    /** Backs [SOURCE_RUNNING]; see the class KDoc for why it is not a supplier that queries. */
    private val sourcesRunning = AtomicLong(0)

    /** Backs `db.events{horizon="all"}`. */
    private val eventsTotal = AtomicLong(0)

    /** Backs `db.events{horizon="future"}`. */
    private val eventsFuture = AtomicLong(0)

    init {
        registry.gauge(SOURCE_RUNNING, sourcesRunning) { it.get().toDouble() }
        registry.gauge(DB_EVENTS, Tags.of(TAG_HORIZON, HORIZON_ALL), eventsTotal) { it.get().toDouble() }
        registry.gauge(DB_EVENTS, Tags.of(TAG_HORIZON, HORIZON_FUTURE), eventsFuture) { it.get().toDouble() }
    }

    /**
     * Records one completed run: how long it took, and how it ended.
     *
     * [outcome] is an [RunOutcome] rather than a free string precisely because a typo in a tag value
     * does not fail anything — it quietly creates a second time series that no alert matches.
     */
    fun recordRun(
        sourceSlug: String,
        outcome: RunOutcome,
        duration: Duration
    ) {
        runTimers
            .computeIfAbsent(sourceSlug) {
                Timer
                    .builder(RUN_DURATION)
                    .description("How long one source's import run took, end to end")
                    .tags(Tags.of(TAG_SOURCE, it))
                    .register(registry)
            }.record(duration.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)

        registry.counter(RUN_OUTCOME, TAG_SOURCE, sourceSlug, TAG_OUTCOME, outcome.tag).increment()

        if (outcome.advancesLastSuccess) {
            markSucceededNow(sourceSlug)
        }
    }

    /**
     * Records what a run did to the database, split by operation.
     *
     * `skipped` is not noise — it is the change-detection working. A source reporting only skips for
     * days is either genuinely static or silently broken, and the pair of this and [RUN_OUTCOME] is
     * what tells those apart.
     */
    fun recordEventsWritten(
        sourceSlug: String,
        operation: WriteOperation,
        count: Int
    ) {
        if (count <= 0) return
        registry
            .counter(EVENTS_WRITTEN, TAG_SOURCE, sourceSlug, TAG_OPERATION, operation.tag)
            .increment(count.toDouble())
    }

    /**
     * Records a scrape failure with its cause, because **a 403 is not a parse failure** and the two
     * need different responses: one is the venue blocking us, the other is the venue's markup having
     * moved. Aggregated into a single counter they are indistinguishable.
     */
    fun recordScrapeFailure(
        sourceSlug: String,
        reason: String
    ) {
        registry.counter(SCRAPE_FAILURES, TAG_SOURCE, sourceSlug, TAG_REASON, reason).increment()
    }

    /**
     * Publishes [epochSeconds] as the source's last success.
     *
     * A **timestamp**, not an age, and that is the Prometheus idiom rather than a preference: an age
     * computed here is only correct at the instant it is scraped, whereas a timestamp lets the alert
     * say `time() - importer_source_last_success_seconds > 3 * interval` and stay true between
     * scrapes. It is also what makes the rule expressible at all without knowing the scrape period.
     */
    fun publishLastSuccess(
        sourceSlug: String,
        epochSeconds: Long
    ) {
        lastSuccessEpochSeconds
            .computeIfAbsent(sourceSlug) { slug ->
                val holder = AtomicLong(0)
                registry.gauge(SOURCE_LAST_SUCCESS, Tags.of(TAG_SOURCE, slug), holder) { it.get().toDouble() }
                holder
            }.set(epochSeconds)
        // A published last-success IS a success, so the two can never disagree about this source.
        publishHasSucceeded(sourceSlug, succeeded = true)
    }

    /**
     * `importer.source.has_succeeded{source}` — **1 if this source has ever completed a run, 0 if it
     * never has**, and the point is that the series exists either way (#618).
     *
     * **A second gauge, rather than reading the first one's absence.** [SOURCE_LAST_SUCCESS] only
     * exists once a source has succeeded, so a venue that has never worked has no series at all —
     * and something with no series cannot be stale, late or failing. Measured on staging: 86
     * sources, 84 series, and the two missing were the only two that were broken, while the
     * dashboard read "0 sources stale".
     *
     * **Not fixed by publishing `last_success = 0` for them.** That asserts a successful import at
     * the epoch — false — and turns every age chart into a 56-year scale. *Never* and *long ago* are
     * different facts.
     *
     * **Not fixed by `absent()` or `unless` in the rule either**, though OpenObserve does implement
     * both (checked against the live instance, unlike `sort_desc`, which it does not). A rule of the
     * form `tracked unless last_success` joins two series at query time and therefore reports every
     * source as never-succeeded during any gap in the *other* metric — and this system has had gaps:
     * #625 dropped roughly half of all metric points for days. One series carrying the fact is
     * atomic; a join between two is only as reliable as the flakier side.
     *
     * The value is refreshed from `event_source` by [MetricsRefreshService] on every tick, so it
     * survives a restart — which an in-process counter of failures does not, and which matters
     * because the importer restarts on every deploy while the import interval is 24 hours.
     */
    fun publishHasSucceeded(
        sourceSlug: String,
        succeeded: Boolean
    ) {
        hasSucceeded
            .computeIfAbsent(sourceSlug) { slug ->
                val holder = AtomicLong(0)
                registry.gauge(SOURCE_HAS_SUCCEEDED, Tags.of(TAG_SOURCE, slug), holder) { it.get().toDouble() }
                holder
            }.set(if (succeeded) 1L else 0L)
    }

    /**
     * `importer.source.events_future{source}` — **how many future events this source holds right
     * now**, refreshed from the database rather than accumulated in the process (#700).
     *
     * **Not a tag on `db.events`.** That is the whole catalogue, and its rule (`ej-catalogue-emptying`)
     * selects with `max`. Per-source series under the same name would put eighty-six smaller series
     * beside the global one: `max` would keep picking the global series only because it is largest,
     * and anything summing `db_events` would double-count. A separate name in the `importer.source.*`
     * family is where an alert author is already looking.
     *
     * **Not `importer.events.written`, which sounds like the same number.** That is a counter living
     * in this process: it resets on every deploy and is absent from `/actuator/prometheus` until
     * something increments it. The import interval is 24 hours and the importer restarts on every
     * deploy, so `increase(...[48h]) == 0` cannot tell "wrote nothing" from "was restarted".
     * ADR-015 records that shape and #618 paid for it — a gauge refreshed from the database survives
     * a restart, because it re-reads state instead of accumulating it.
     *
     * **Zero is a value here, not an absence**, and [MetricsRefreshService] publishes it for every
     * enabled source that the count query returns no row for. A source missing from the exposition
     * cannot be alerted on and reads as healthy, which is the #618 failure exactly.
     *
     * **Zero is also not the same as broken**, which is a constraint on the rule rather than on this
     * meter: a venue with nothing on for three weeks is legitimately at zero. The alert therefore
     * asks for zero *now* against a non-zero recent history for the same series, not for a floor.
     */
    fun publishFutureEvents(
        sourceSlug: String,
        count: Long
    ) {
        futureEvents
            .computeIfAbsent(sourceSlug) { slug ->
                val holder = AtomicLong(0)
                registry.gauge(SOURCE_EVENTS_FUTURE, Tags.of(TAG_SOURCE, slug), holder) { it.get().toDouble() }
                holder
            }.set(count)
    }

    /**
     * `importer.source.field_coverage{source,field}` — the fraction of a run's events carrying one
     * field (#472).
     *
     * **Registered here rather than in a second metrics path**, which is what the issue asks for and
     * is right for a reason worth stating: a meter is only useful next to the meters it is compared
     * with. A field-coverage gauge in its own registry could not be graphed against
     * `importer.run.outcome` or `importer.events.written`, and those three together are what tell
     * "the venue changed its page" apart from "the scraper broke" apart from "nothing happened".
     *
     * A ratio rather than a count, because the count is meaningless without the run size and the
     * run size is already `importer.events.written`. Cardinality is sources times fields — tens,
     * and both sides are ours.
     */
    fun publishFieldCoverage(
        sourceSlug: String,
        field: String,
        ratio: Double
    ) {
        fieldCoverage
            .computeIfAbsent(sourceSlug to field) { (slug, name) ->
                val holder =
                    java.util.concurrent.atomic
                        .AtomicReference(0.0)
                registry.gauge(FIELD_COVERAGE, Tags.of(TAG_SOURCE, slug, TAG_FIELD, name), holder) { it.get() }
                holder
            }.set(ratio)
    }

    private fun markSucceededNow(sourceSlug: String) = publishLastSuccess(sourceSlug, System.currentTimeMillis() / MILLIS_PER_SECOND)

    /** Called by [MetricsRefreshService]; see the class KDoc for why a scheduler rather than a supplier. */
    fun updateSourcesRunning(count: Long) = sourcesRunning.set(count)

    /** Called by [MetricsRefreshService]. */
    fun updateEventCounts(
        total: Long,
        future: Long
    ) {
        eventsTotal.set(total)
        eventsFuture.set(future)
    }

    /**
     * How a run ended.
     *
     * **`partial` from PLATFORM_SETUP.md §7 is deliberately absent**, and the deviation is worth
     * stating rather than quietly papering over: this pipeline has no partial outcome. A run either
     * completes and upserts, is skipped because the source was unchanged or already claimed, or
     * throws — and the upserts are inside one transaction, so there is no half-written state to
     * name. Inventing a bucket nothing can ever emit would make a dashboard panel that is always
     * zero, which reads as "never happens" rather than "cannot happen". These five are what the code
     * can actually produce.
     */
    enum class RunOutcome(
        val tag: String,
        /**
         * Whether this outcome advances `importer.source.last_success`.
         *
         * **It has to agree with what [EventImportService] writes to `last_success_at`, and this flag
         * is where that agreement is written down.** The gauge has two feeds — this one, which is
         * immediate and in-memory, and [MetricsRefreshService], which republishes from the column
         * every minute. If the two disagreed, the gauge would jump backwards or forwards once a
         * minute and the disagreement would look like clock skew rather than like a bug. The rule is
         * one line: **an outcome advances last-success exactly when the run reached the source and
         * got an answer**, which is what `markSuccess` is called for.
         */
        val advancesLastSuccess: Boolean
    ) {
        /** The source was scraped and its events upserted. */
        SUCCESS("success", advancesLastSuccess = true),

        /**
         * The source answered 304 — nothing to do, and not a failure.
         *
         * It advances last-success because a 304 is a **working** scraper: the request went out, the
         * venue answered, and the conditional headers did their job. Treating it as "no success" would
         * make a stable venue look like a broken one after three quiet days, which is the false
         * positive that gets a staleness alert muted.
         */
        NOT_MODIFIED("not_modified", advancesLastSuccess = true),

        /** The run threw. Transient by assumption, so it consumes retry budget. */
        FAILED("failed", advancesLastSuccess = false),

        /** Unknown source type, or no importer deployed for it. Will never self-resolve on retry. */
        MISCONFIGURED("misconfigured", advancesLastSuccess = false),

        /** Another run already held the claim (ADR-009), so this one did nothing. */
        SKIPPED("skipped", advancesLastSuccess = false)
    }

    /** What an upsert did to a row. */
    enum class WriteOperation(
        val tag: String
    ) {
        /** The event did not exist and was written. */
        INSERTED("inserted"),

        /** The event existed and its content had changed. */
        UPDATED("updated"),

        /** The event existed and was byte-identical, so no UPDATE was issued. */
        SKIPPED("skipped")
    }

    companion object {
        const val RUN_DURATION = "importer.run.duration"
        const val RUN_OUTCOME = "importer.run.outcome"
        const val EVENTS_WRITTEN = "importer.events.written"
        const val SCRAPE_FAILURES = "importer.scrape.failures"
        const val SOURCE_LAST_SUCCESS = "importer.source.last_success"

        /**
         * `importer.source.has_succeeded{source}` — the series that exists for a source which has
         * never worked, which [SOURCE_LAST_SUCCESS] deliberately does not. See [publishHasSucceeded].
         */
        const val SOURCE_HAS_SUCCEEDED = "importer.source.has_succeeded"

        /**
         * `importer.source.events_future{source}` — the per-source half of ADR-015's criterion 1,
         * which `db.events` can only see once the whole catalogue has drained. See
         * [publishFutureEvents].
         *
         * **Not `events.total` or anything ending in `_total`.** That is Prometheus' reserved suffix
         * for counters and Micrometer strips it, silently, which is how `db.events.total` came to be
         * published as `db_events` while every rule written from the documented name matched
         * nothing.
         */
        const val SOURCE_EVENTS_FUTURE = "importer.source.events_future"
        const val SOURCE_RUNNING = "importer.source.running"

        /**
         * `importer.source.field_coverage{source,field}` — #415's list plus one dimension (#472).
         *
         * The series to alert on is a **drop against this source's own history**, not a threshold: a
         * venue that has never published a price sits at 0 forever and is not broken.
         */
        const val FIELD_COVERAGE = "importer.source.field_coverage"
        const val TAG_FIELD = "field"

        /**
         * **One gauge with a `horizon` tag, not the two separate names PLATFORM_SETUP.md §7 lists —
         * and the deviation is forced rather than preferred.**
         *
         * `db.events.total` cannot survive contact with Prometheus. `_total` is the reserved suffix
         * for counters, so Micrometer's Prometheus naming convention *strips* it: the meter would be
         * published as `db_events`, silently, while every dashboard and alert written from the
         * documented name looked for `db_events_total` and matched nothing. Measured, not assumed —
         * the exposition showed `db_events 0.0` next to `db_events_future 0.0`.
         *
         * Given the rename was unavoidable, a tag is the right shape for it anyway: "all events" and
         * "future events" are the same measurement over two windows, which is what a label is for,
         * and `db_events{horizon="future"}` is one PromQL selector rather than a second metric name
         * to remember. §7 has been updated to match.
         */
        const val DB_EVENTS = "db.events"
        const val TAG_HORIZON = "horizon"
        const val HORIZON_ALL = "all"
        const val HORIZON_FUTURE = "future"

        const val TAG_SOURCE = "source"
        const val TAG_OUTCOME = "outcome"
        const val TAG_OPERATION = "operation"
        const val TAG_REASON = "reason"

        private const val MILLIS_PER_SECOND = 1000L
    }
}
