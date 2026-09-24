package de.norm.events.scraper

import de.norm.events.translation.TranslationResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * Every meter the importer publishes, and the only place their names and tags are written down.
 * A scraper does not fail loudly: when a venue redesigns its site the importer reports success
 * and silently writes zero events, which no HTTP monitoring sees and a business metric with an
 * alert does (ADR-015). Names and tags follow `docs/ops/PLATFORM_SETUP.md` §7, which the
 * dashboards and alert rules are written against; renaming a meter here silently breaks an alert.
 *
 * Counters are recorded here; gauges are refreshed elsewhere. Micrometer reads a gauge at scrape
 * time on the actuator thread, and every query here is reactive, so each gauge reads an
 * [AtomicLong] that [MetricsRefreshService] updates on a schedule. That is also why the gauges
 * survive a deploy and the counters do not: the importer restarts on every deploy against a
 * 24-hour interval, so a counter is absent far more often than it is wrong (#618).
 */
@Component
@Suppress("TooManyFunctions") // A metrics facade is one function per meter, and the meter names are the interface.
class ImporterMetrics(
    private val registry: MeterRegistry
) {
    /**
     * Timers, one per source, created on first use; Micrometer de-duplicates by name and tags, and
     * the cache makes "one timer per source" legible.
     */
    private val runTimers = ConcurrentHashMap<String, Timer>()

    /**
     * Epoch-second of each source's last successful run, backing the `last_success` gauge. A map,
     * because sources are created through the admin API at runtime.
     */
    private val lastSuccessEpochSeconds = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Whether each source has ever succeeded, backing the `has_succeeded` gauge. Separate from
     * [lastSuccessEpochSeconds] because the point is an entry for sources the other map lacks.
     */
    private val hasSucceeded = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Future events currently held per source, backing the `events_future` gauge (#700).
     */
    private val futureEvents = ConcurrentHashMap<String, AtomicLong>()

    /** Days since each source last held a future event, backing the `days_since_future_event` gauge (#1498). */
    private val daysSinceFutureEvent = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Per-source, per-field coverage ratios; a `Double` holder because this is the one fraction here.
     */
    private val fieldCoverage = ConcurrentHashMap<Pair<String, String>, java.util.concurrent.atomic.AtomicReference<Double>>()

    /** Backs [SOURCE_RUNNING]; see the class KDoc for why it is not a supplier that queries. */
    private val sourcesRunning = AtomicLong(0)

    /** Backs `db.events{horizon="all"}`. */
    private val eventsTotal = AtomicLong(0)

    /** Backs `db.events{horizon="future"}`. */
    private val eventsFuture = AtomicLong(0)

    /** Backs [MUSICBRAINZ_UNCHECKED]: artist rows the MusicBrainz sweep has not looked at yet (#1567). */
    private val musicBrainzUnchecked = AtomicLong(0)

    /** Backs [MUSICBRAINZ_UNENRICHED]: EXACT rows whose entity step C has not read yet (#1568). */
    private val musicBrainzUnenriched = AtomicLong(0)

    /** Sources currently `FAILED`, per reason, backing the `sources.failed` gauge (#708); keyed by reason. */
    private val failedSources = ConcurrentHashMap<String, AtomicLong>()

    init {
        registry.gauge(SOURCE_RUNNING, sourcesRunning) { it.get().toDouble() }
        registry.gauge(DB_EVENTS, Tags.of(TAG_HORIZON, HORIZON_ALL), eventsTotal) { it.get().toDouble() }
        registry.gauge(DB_EVENTS, Tags.of(TAG_HORIZON, HORIZON_FUTURE), eventsFuture) { it.get().toDouble() }
        registry.gauge(MUSICBRAINZ_UNCHECKED, musicBrainzUnchecked) { it.get().toDouble() }
        registry.gauge(MUSICBRAINZ_UNENRICHED, musicBrainzUnenriched) { it.get().toDouble() }
    }

    /**
     * Records one completed run. [outcome] is an [RunOutcome] rather than a string because a typo
     * in a tag value quietly creates a second series no alert matches.
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
     * Records what a run did to the database, by operation. `skipped` is the change-detection
     * working; with [RUN_OUTCOME] it tells a static source from a silently broken one.
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
     * Every meter one upsert produces, three writes and four drops (#982), in one call because the
     * mapping from an outcome to its meters is this class's job. [droppedUnresolvedDate] is separate
     * because it is dropped before the upsert. The `reason` values are constants, as for
     * [scrapeFailureReason]: a tag fed by a title or a URL is unbounded.
     */
    fun recordUpsertOutcome(
        sourceSlug: String,
        upsert: UpsertOutcome,
        droppedUnresolvedDate: Int
    ) {
        recordEventsWritten(sourceSlug, WriteOperation.INSERTED, upsert.inserted)
        recordEventsWritten(sourceSlug, WriteOperation.UPDATED, upsert.updated)
        recordEventsWritten(sourceSlug, WriteOperation.SKIPPED, upsert.skipped)

        // A loop, so this stays one function under detekt's count. The `> 0` guard: a counter
        // incremented by zero still creates the series, making "dropped events" true of every source.
        mapOf(
            DropReason.PAST to upsert.droppedPast,
            DropReason.DUPLICATE to upsert.droppedDuplicate,
            DropReason.UNRESOLVED_DATE to droppedUnresolvedDate,
            DropReason.SLUG_CONFLICT to upsert.droppedSlugConflict
        ).forEach { (reason, count) ->
            if (count > 0) {
                registry.counter(EVENTS_DROPPED, TAG_SOURCE, sourceSlug, TAG_REASON, reason.tag).increment(count.toDouble())
            }
        }
    }

    /**
     * Records a scrape failure with its cause, because a 403 is not a parse failure: one is the
     * venue blocking us, the other its markup having moved.
     */
    fun recordScrapeFailure(
        sourceSlug: String,
        reason: String
    ) {
        registry.counter(SCRAPE_FAILURES, TAG_SOURCE, sourceSlug, TAG_REASON, reason).increment()
    }

    /**
     * Counts one attempt to translate a description as `written`, `rejected` or `failed`. A rejection
     * is the engine's own checks at work. `ej-translations-failing` reads the share of `failed` (#1822).
     */
    fun recordTranslation(result: TranslationResult) {
        val outcome =
            when (result) {
                is TranslationResult.Translated -> "written"
                TranslationResult.Rejected -> "rejected"
                TranslationResult.Failed -> "failed"
            }
        registry.counter(TRANSLATIONS, TAG_OUTCOME, outcome).increment()
    }

    /**
     * Counts one MusicBrainz lookup by verdict, `exact`, `ambiguous`, `none`, or `error`. The shares
     * are the number ADR-031 was decided on.
     */
    fun recordMusicBrainzLookup(state: String) {
        registry.counter(MUSICBRAINZ_LOOKUPS, TAG_STATE, state).increment()
    }

    /**
     * Counts one head pass, the part before ` - ` or `: ` of a name MusicBrainz did not know.
     * Reported, never stored (#1145).
     */
    fun recordMusicBrainzHead(state: String) {
        registry.counter(MUSICBRAINZ_HEADS, TAG_STATE, state).increment()
    }

    /** Publishes how many artist rows are still `UNCHECKED`; refreshed by [MetricsRefreshService]. */
    fun updateMusicBrainzUnchecked(count: Long) = musicBrainzUnchecked.set(count)

    /** Counts one column family the enrichment filled: `website`, `bandcamp`, `type`, `image`, … (ADR-031, step C). */
    fun recordMusicBrainzEnriched(field: String) {
        registry.counter(MUSICBRAINZ_ENRICHED, TAG_FIELD, field).increment()
    }

    /** Counts one Commons picture the enrichment refused, by `licence`, `author`, `source`, `mime` or `size`. */
    fun recordMusicBrainzImageRefused(reason: String) {
        registry.counter(MUSICBRAINZ_IMAGE_REFUSED, TAG_REASON, reason).increment()
    }

    /** Counts one ensemble's Wikipedia lead the enrichment did not store, by `birth-data`, `short` or `no-article`. */
    fun recordWikipediaDescriptionRefused(reason: String) {
        registry.counter(WIKIPEDIA_REFUSED, TAG_REASON, reason).increment()
    }

    /** Counts one entity read that could not complete: MusicBrainz or Wikimedia unavailable. */
    fun recordMusicBrainzEnrichmentError() {
        registry.counter(MUSICBRAINZ_ENRICHED, TAG_FIELD, FIELD_ERROR).increment()
    }

    /** Publishes how many EXACT rows still await their entity read; refreshed by [MetricsRefreshService]. */
    fun updateMusicBrainzUnenriched(count: Long) = musicBrainzUnenriched.set(count)

    /**
     * Publishes [epochSeconds] as the source's last success. A timestamp, not an age, the
     * Prometheus idiom: an age is only correct at the instant it is scraped, while
     * `time() - importer_source_last_success_seconds > 3 * interval` stays true between scrapes.
     */
    fun publishLastSuccess(
        sourceSlug: String,
        epochSeconds: Long
    ) {
        lastSuccessEpochSeconds.publishPerSource(sourceSlug, SOURCE_LAST_SUCCESS, epochSeconds)
        // A published last-success IS a success, so the two can never disagree about this source.
        publishHasSucceeded(sourceSlug, succeeded = true)
    }

    /**
     * `importer.source.has_succeeded{source}`: 1 if this source has ever completed a run, 0 if
     * never, and the series exists either way (#618). [SOURCE_LAST_SUCCESS] only exists once a
     * source has succeeded, so a venue that has never worked cannot be stale: 86 sources, 84
     * series on staging, and the two missing were the only two broken. Not fixed by publishing
     * `last_success = 0`, which asserts a success at the epoch, nor by `absent()` or `unless` in
     * the rule, which joins two series and reports every source as never-succeeded during a gap in
     * the other (#625 dropped half of all points for days). One series carrying the fact is atomic.
     */
    fun publishHasSucceeded(
        sourceSlug: String,
        succeeded: Boolean
    ) {
        hasSucceeded.publishPerSource(sourceSlug, SOURCE_HAS_SUCCEEDED, if (succeeded) 1L else 0L)
    }

    /**
     * `importer.source.events_future{source}`: how many future events this source holds now,
     * refreshed from the database (#700). Not a tag on `db.events`, whose rule selects with `max`
     * and would keep picking the global series. Not `importer.events.written`, a counter that
     * cannot tell "wrote nothing" from "was restarted". Zero is a value: [MetricsRefreshService]
     * publishes it for every enabled source the count query returns no row for, since a source
     * missing from the exposition reads as healthy. Zero is also not broken: a venue with nothing
     * on for three weeks is legitimately at zero, so the rule asks for zero now against a non-zero
     * recent history.
     */
    fun publishFutureEvents(
        sourceSlug: String,
        count: Long
    ) {
        futureEvents.publishPerSource(sourceSlug, SOURCE_EVENTS_FUTURE, count)
    }

    /**
     * `importer.source.days_since_future_event{source,known_quiet}`: how long this source has held
     * no future event, in days (#1498). `ej-source-emptied` reads [SOURCE_EVENTS_FUTURE] against a
     * week of history, so four sources sat at zero for a year and no rule could see them; a
     * duration needs no window, `> 30` is the rule. The `known_quiet` tag carries
     * [KNOWN_QUIET_SOURCES] so the rule can select the sources nobody has accounted for.
     */
    fun publishDaysSinceFutureEvent(
        sourceSlug: String,
        days: Long,
        knownQuiet: Boolean
    ) {
        daysSinceFutureEvent.publishPerSource(
            sourceSlug,
            SOURCE_DAYS_SINCE_FUTURE_EVENT,
            days,
            Tags.of(TAG_SOURCE, sourceSlug, TAG_KNOWN_QUIET, knownQuiet.toString())
        )
    }

    /**
     * Sets [value] on this map's holder for [sourceSlug], registering [meterName] the first time the
     * source is seen; [tags] reach the registry at that first registration only.
     */
    private fun ConcurrentHashMap<String, AtomicLong>.publishPerSource(
        sourceSlug: String,
        meterName: String,
        value: Long,
        tags: Tags = Tags.of(TAG_SOURCE, sourceSlug)
    ) {
        computeIfAbsent(sourceSlug) { _ ->
            val holder = AtomicLong(0)
            registry.gauge(meterName, tags, holder) { it.get().toDouble() }
            holder
        }.set(value)
    }

    /**
     * `importer.source.field_coverage{source,field}`, the fraction of a run's events carrying one
     * field (#472). Registered here because a meter is only useful next to the meters it is compared
     * with: this, `importer.run.outcome` and `importer.events.written` together tell "the venue
     * changed its page" from "the scraper broke" from "nothing happened". A ratio, because the run
     * size is already `importer.events.written`. Cardinality is sources times fields, tens.
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

    /**
     * `importer.sources.failed{reason}`: how many enabled sources sit `FAILED` on this reason now,
     * from `event_source.last_failure_reason` (#708). The counter `importer.scrape.failures` says a
     * failure happened; this says how many sources are in it at once, which is the difference between
     * one venue's DNS and the cluster's. Refreshed from the database, so a deploy does not blank it,
     * and published for every reason so a zero is a zero and not an absence.
     */
    fun publishFailedSources(
        reason: String,
        count: Long
    ) {
        failedSources.publishPerSource(reason, SOURCES_FAILED, count, Tags.of(TAG_REASON, reason))
    }

    /** Called by [MetricsRefreshService]. */
    fun updateEventCounts(
        total: Long,
        future: Long
    ) {
        eventsTotal.set(total)
        eventsFuture.set(future)
    }

    /**
     * How a run ended. `partial` from PLATFORM_SETUP.md §7 is absent: the upserts are inside one
     * transaction, so there is no half-written state, and a bucket nothing can emit reads as "never
     * happens" rather than "cannot happen".
     */
    enum class RunOutcome(
        val tag: String,
        /**
         * Whether this outcome advances `importer.source.last_success`. It has to agree with what
         * [EventImportService] writes to `last_success_at`: the gauge has two feeds, this one and
         * [MetricsRefreshService] republishing from the column every minute, and a disagreement would
         * look like clock skew. An outcome advances last-success exactly when the run reached the source
         * and got an answer.
         */
        val advancesLastSuccess: Boolean
    ) {
        /** The source was scraped and its events upserted. */
        SUCCESS("success", advancesLastSuccess = true),

        /**
         * The source answered 304. It advances last-success because a 304 is a working scraper; treating
         * it as "no success" makes a stable venue look broken after three quiet days.
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

    /**
     * Why an event the run scraped never reached the database. Only reasons computed in shared code
     * appear, so every value is counted for every importer; the ~93 per-venue parse drops need each
     * overview scraper to report rows-seen against events-returned (#982).
     */
    enum class DropReason(
        val tag: String
    ) {
        /** Dated before today, so already over by the time the run saw it. */
        PAST("past"),

        /** A repeated `sourceId` or date+title+time within one scrape. */
        DUPLICATE("duplicate"),

        /** Neither the overview nor the detail page yielded a date. */
        UNRESOLVED_DATE("unresolved_date"),

        /** Another event already holds the slug the event needs (#1719). */
        SLUG_CONFLICT("slug_conflict")
    }

    companion object {
        const val RUN_DURATION = "importer.run.duration"
        const val RUN_OUTCOME = "importer.run.outcome"
        const val EVENTS_WRITTEN = "importer.events.written"
        const val SCRAPE_FAILURES = "importer.scrape.failures"
        const val EVENTS_DROPPED = "importer.events.dropped"
        const val SOURCE_LAST_SUCCESS = "importer.source.last_success"

        /** `importer.translations{outcome}` — attempts and how many produced a text. See [recordTranslation]. */
        const val TRANSLATIONS = "importer.translations"

        /** `importer.musicbrainz.lookups{state}` — one per name looked up. See [recordMusicBrainzLookup]. */
        const val MUSICBRAINZ_LOOKUPS = "importer.musicbrainz.lookups"

        /** `importer.musicbrainz.heads{state}` — the head pass, reported only. See [recordMusicBrainzHead]. */
        const val MUSICBRAINZ_HEADS = "importer.musicbrainz.heads"

        /** `importer.musicbrainz.unchecked` — rows still awaiting a verdict; the backfill draining. */
        const val MUSICBRAINZ_UNCHECKED = "importer.musicbrainz.unchecked"

        /** `importer.musicbrainz.enriched{field}` — one per column family step C filled, `error` for a read that failed. */
        const val MUSICBRAINZ_ENRICHED = "importer.musicbrainz.enriched"

        /** `importer.musicbrainz.image_refused{reason}` — a Commons picture step C would not store. */
        const val MUSICBRAINZ_IMAGE_REFUSED = "importer.musicbrainz.image_refused"
        const val WIKIPEDIA_REFUSED = "importer.wikipedia.refused"

        /** `importer.musicbrainz.unenriched` — EXACT rows awaiting their entity read; step C's backfill draining. */
        const val MUSICBRAINZ_UNENRICHED = "importer.musicbrainz.unenriched"

        const val FIELD_ERROR = "error"

        const val TAG_STATE = "state"

        /**
         * `importer.source.has_succeeded{source}`, the series that exists for a source which has never
         * worked. See [publishHasSucceeded].
         */
        const val SOURCE_HAS_SUCCEEDED = "importer.source.has_succeeded"

        /**
         * `importer.source.events_future{source}`, the per-source half of ADR-015's criterion 1. See
         * [publishFutureEvents]; not `_total`, see [DB_EVENTS].
         */
        const val SOURCE_EVENTS_FUTURE = "importer.source.events_future"

        /**
         * `importer.source.days_since_future_event{source,known_quiet}` (#1498). See
         * [publishDaysSinceFutureEvent].
         */
        const val SOURCE_DAYS_SINCE_FUTURE_EVENT = "importer.source.days_since_future_event"
        const val TAG_KNOWN_QUIET = "known_quiet"
        const val SOURCE_RUNNING = "importer.source.running"

        /** `importer.sources.failed{reason}` (#708). See [publishFailedSources]. */
        const val SOURCES_FAILED = "importer.sources.failed"

        /**
         * `importer.source.field_coverage{source,field}` (#472). The series to alert on is a drop
         * against this source's own history: a venue that has never published a price sits at 0.
         */
        const val FIELD_COVERAGE = "importer.source.field_coverage"
        const val TAG_FIELD = "field"

        /**
         * One gauge with a `horizon` tag, not the two names PLATFORM_SETUP.md §7 lists, forced:
         * `_total` is the reserved suffix for counters, so Micrometer strips it and `db.events.total`
         * was published as `db_events`, measured in the exposition. "All events" and "future events"
         * are the same measurement over two windows, which is what a label is for; §7 states this shape.
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
