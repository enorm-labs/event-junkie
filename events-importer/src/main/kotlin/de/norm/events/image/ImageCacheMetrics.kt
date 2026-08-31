package de.norm.events.image

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Every meter the image cache publishes, and the only place their names and tags are written down.
 *
 * **ADR-019 and ADR-020 shipped the subsystem with no instrumentation, and #843 paid for it.** The
 * one question a rollout turns on — is the backfill done? — had two answers available, and both were
 * accidents: counting lines in a log the node rotated mid-run, and a generic per-host HTTP counter
 * that happened to name the imgproxy sidecar. Neither is something to build an alert on.
 *
 * The split is [ImporterMetrics][de.norm.events.scraper.ImporterMetrics]': **counters are recorded
 * where the thing happens, gauges read an [AtomicLong] that [ImageMetricsRefreshService] refreshes**.
 * Micrometer reads a gauge synchronously on the thread serving the actuator endpoint, and every
 * query this module can make suspends, so a supplier that asked the database would block a Netty
 * event-loop thread.
 *
 * **No `host` tag anywhere, and no timer.** Per-host fetch latency already exists:
 * `http_client_requests_seconds{client_name}` is published for the throttled WebClient this module
 * fetches through. A second copy would add a histogram per venue to the store that #625 is about.
 */
@Component
class ImageCacheMetrics(
    private val registry: MeterRegistry
) {
    /** One holder per URL state, all four registered at construction so none can be absent. */
    private val urlStates = UrlState.entries.associateWith { state -> gauge(URLS, TAG_STATE, state.tag) }

    /** Backs [DERIVATIVE_BACKLOG]; see the class KDoc for why it is not a supplier that queries. */
    private val derivativeBacklog = gauge(DERIVATIVE_BACKLOG, null, null)

    private val sweepCandidates = SweepKind.entries.associateWith { kind -> gauge(SWEEP_CANDIDATES, TAG_KIND, kind.tag) }

    /**
     * Every counter, created at construction rather than on first increment.
     *
     * **A counter that has never incremented is absent from the exposition, and an absent series
     * cannot fire a rule.** `deploy/alerts/README.md` records what that cost: a rule summing two
     * counters was un-fireable during exactly the normal operation it watched, because one side had
     * no series yet. Registering at zero here means a rule against any of these is evaluable from
     * the first scrape after a restart.
     */
    private val fetchOutcomes = FetchOutcome.entries.associateWith { counter(FETCH, TAG_OUTCOME, it.tag) }
    private val derivativeOutcomes = DerivativeOutcomeTag.entries.associateWith { counter(DERIVATIVES, TAG_OUTCOME, it.tag) }
    private val sweepDeletions = SweepKind.entries.associateWith { counter(SWEEP_DELETED, TAG_KIND, it.tag) }

    /** Records one fetch pass. Batched rather than per URL: a counter sums either way. */
    fun recordFetchPass(outcome: CacheOutcome) {
        fetchOutcomes.getValue(FetchOutcome.FETCHED).increment(outcome.fetched.toDouble())
        fetchOutcomes.getValue(FetchOutcome.UNCHANGED).increment(outcome.unchanged.toDouble())
        fetchOutcomes.getValue(FetchOutcome.FAILED).increment(outcome.failed.toDouble())
    }

    /**
     * Records one derivative pass.
     *
     * `refused` counts a width imgproxy would not render **and** a store that would not take the
     * bytes, exactly as [DerivativeOutcome] conflates them. Neither fails the image, so a rising
     * refusal rate against a backlog that is not draining is the signal, not either one alone.
     */
    fun recordDerivativePass(outcome: DerivativeOutcome) {
        derivativeOutcomes.getValue(DerivativeOutcomeTag.WRITTEN).increment(outcome.variants.toDouble())
        derivativeOutcomes.getValue(DerivativeOutcomeTag.REFUSED).increment(outcome.refused.toDouble())
    }

    /**
     * Records one sweep: what it found, and what it removed.
     *
     * **The gauges are published whether or not anything was deleted**, which is the whole point.
     * `app.images.sweep.enabled` exists so a rule can be watched before it is trusted, and in that
     * mode the only report was a log line every six hours. The counters move only when [deleting].
     *
     * They are as stale as the sweep's own tick, six hours, and that is the number they describe —
     * unlike the gauges above, which the one-minute refresh keeps current.
     */
    fun recordSweep(
        outcome: RemovalOutcome,
        deleting: Boolean
    ) {
        sweepCandidates.getValue(SweepKind.ROWS).set(outcome.images.toLong())
        sweepCandidates.getValue(SweepKind.STRAYS).set(outcome.strays.toLong())
        if (!deleting) return
        sweepDeletions.getValue(SweepKind.ROWS).increment(outcome.images.toDouble())
        sweepDeletions.getValue(SweepKind.OBJECTS).increment(outcome.objects.toDouble())
        sweepDeletions.getValue(SweepKind.STRAYS).increment(outcome.strays.toDouble())
    }

    /** Called by [ImageMetricsRefreshService]; see the class KDoc for why a scheduler rather than a supplier. */
    fun updateUrlStates(counts: ImageUrlCountsRow) {
        urlStates.getValue(UrlState.CACHED).set(counts.cached)
        urlStates.getValue(UrlState.FAILED).set(counts.failed)
        urlStates.getValue(UrlState.PENDING).set(counts.pending)
        urlStates.getValue(UrlState.WITHHELD).set(counts.withheld)
    }

    fun updateDerivativeBacklog(images: Long) = derivativeBacklog.set(images)

    private fun gauge(
        name: String,
        tagKey: String?,
        tagValue: String?
    ): AtomicLong {
        val holder = AtomicLong(0)
        val tags = if (tagKey == null || tagValue == null) Tags.empty() else Tags.of(tagKey, tagValue)
        registry.gauge(name, tags, holder) { it.get().toDouble() }
        return holder
    }

    private fun counter(
        name: String,
        tagKey: String,
        tagValue: String
    ): Counter = registry.counter(name, tagKey, tagValue)

    /**
     * The four states a referenced image URL can be in.
     *
     * **Disjoint, and together every URL the site references**, which is what makes them stackable.
     * `withheld` is a takedown rather than outstanding work: that URL is never fetched again, so
     * counting it as pending would show a backlog that can never drain.
     */
    enum class UrlState(
        val tag: String
    ) {
        /** A live row holding a content hash — the bytes are in the bucket. */
        CACHED("cached"),

        /** Tried and refused, and inside its cooldown. `failure_reason` on the row says why. */
        FAILED("failed"),

        /** No row, or a row that has neither succeeded nor failed. This is the backfill's queue. */
        PENDING("pending"),

        /** Tombstoned by a venue takedown (`SCRAPING_POSITION.md` §5). */
        WITHHELD("withheld")
    }

    /** What one URL did on a fetch pass, mirroring [CacheOutcome]'s three fields. */
    enum class FetchOutcome(
        val tag: String
    ) {
        FETCHED("fetched"),

        /** The venue answered 304. A working fetch, not a no-op. */
        UNCHANGED("unchanged"),

        /**
         * The URL was refused, or the bucket would not take the bytes.
         *
         * **No `reason` tag**, and that is [ImageFetcher]'s existing decision rather than a new one:
         * a rejection reason is built from an exception class or a size, so it is unbounded. It is
         * stored on the row, where an operator reads it per image.
         */
        FAILED("failed")
    }

    /** What one derivative pass produced. Named for the tag values, not for [DerivativeOutcome]'s fields. */
    enum class DerivativeOutcomeTag(
        val tag: String
    ) {
        /** One variant file written to the bucket and recorded. */
        WRITTEN("written"),

        /** imgproxy declined the render, or the store declined the bytes. */
        REFUSED("refused")
    }

    /** What a sweep counts, on both the candidate gauge and the deletion counter. */
    enum class SweepKind(
        val tag: String
    ) {
        /** `cached_image` rows nothing points at any more. */
        ROWS("rows"),

        /** Objects belonging to those rows. Only ever deleted, never a candidate on its own. */
        OBJECTS("objects"),

        /** Objects in the bucket that no live row claims — the listing's answer. */
        STRAYS("strays")
    }

    companion object {
        /**
         * `images.urls{state}` — the backfill seen from the database.
         *
         * **Not `images.urls.total`.** `_total` is Prometheus' reserved counter suffix and Micrometer
         * strips it silently, which is how `db.events.total` became `db_events` and broke every rule
         * written against the documented name (PLATFORM_SETUP.md §7).
         */
        const val URLS = "images.urls"

        /** `images.derivatives.backlog` — stored images still short of their variants. */
        const val DERIVATIVE_BACKLOG = "images.derivatives.backlog"

        /** `images.sweep.candidates{kind}` — what the last sweep would delete, deleting or not. */
        const val SWEEP_CANDIDATES = "images.sweep.candidates"

        const val FETCH = "images.fetch"
        const val DERIVATIVES = "images.derivatives"
        const val SWEEP_DELETED = "images.sweep.deleted"

        const val TAG_STATE = "state"
        const val TAG_OUTCOME = "outcome"
        const val TAG_KIND = "kind"
    }
}
