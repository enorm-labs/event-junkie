package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.pow

/**
 * Periodic scheduler for event imports.
 *
 * Runs a tick every 60 seconds to find event sources that are due and delegates
 * each to [EventImportService.importFromSource]. This is a thin orchestration layer
 * on top of the existing import infrastructure — it adds:
 *
 * - **Per-source scheduling**: each source has its own `importIntervalMinutes`.
 * - **Retry with capped exponential backoff**: failed sources are retried up to `maxRetries`
 *   times, with the interval doubling on each consecutive failure but never exceeding six
 *   hours. A source that spends its retry budget returns to its normal interval — it is never
 *   dropped from the schedule.
 * - **Staleness detection**: sources stuck in RUNNING for >30 min are reset to FAILED.
 * - **Overlap prevention**: sources with status = RUNNING are skipped.
 * - **Misconfiguration detection**: sources with status = MISCONFIGURED are skipped entirely
 *   (they have a permanent config error that requires manual intervention).
 *
 * Scheduling can be disabled via `app.scheduling.enabled=false` (e.g. in tests).
 *
 * @see EventSourceEntity for scheduling fields
 * @see EventImportService for the import pipeline
 */
@Service
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class ScheduledImportService(
    private val eventSourceRepository: EventSourceRepository,
    private val eventImportService: EventImportService,
    private val clock: Clock = Clock.systemUTC(),
    /** Configurable staleness timeout — sources stuck in RUNNING longer than this are reset to FAILED. */
    @Value($$"${app.scheduling.staleness-timeout:30m}")
    private val stalenessTimeout: Duration = DEFAULT_STALENESS_TIMEOUT
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Main scheduler tick — runs every 60 seconds.
     *
     * Spring Boot 4 (Spring Framework 7) natively supports Kotlin `suspend` functions
     * in `@Scheduled` methods, so no `runBlocking` bridge is needed. Spring dispatches
     * the coroutine on an appropriate scheduler, keeping the Netty event loop free.
     *
     * Note: `$$"..."` uses Kotlin multi-dollar raw string to pass Spring property placeholder
     * without interpolation — `$$` raises the interpolation threshold so `${...}` is literal.
     */
    @Scheduled(fixedDelayString = $$"${app.scheduling.tick-interval:60000}")
    suspend fun tick() {
        resetStuckSources()
        importDueSources()
    }

    /**
     * Finds and imports all sources that are due based on their individual schedule.
     *
     * A source is due when its `lastImportAt` + `importIntervalMinutes` is in the past.
     * Failed sources use exponential backoff: `importIntervalMinutes × 2^retryCount`.
     */
    private suspend fun importDueSources() {
        // Capture a single timestamp for the entire tick to ensure consistent
        // due-date evaluation across all sources (avoids clock drift within a tick).
        val now = Instant.now(clock)
        val candidates = eventSourceRepository.findDueForImport(now).toList()

        // Filter to sources that are actually due based on their individual interval + backoff
        val dueSources = candidates.filter { isDue(it, now) }

        if (dueSources.isEmpty()) return

        logger.info { "Scheduler tick: ${dueSources.size} source(s) due for import" }

        // Concurrent execution is safe — per-host politeness is enforced by PerHostThrottlingFilter,
        // the artist cache is local to each importFromSource call, and each source runs in its own transaction.
        eventImportService.importConcurrently(dueSources)
    }

    /**
     * Checks if a source is due for import based on its schedule and retry backoff.
     *
     * A source is due when:
     * - It has never been imported, OR
     * - Enough time has passed since the last import to satisfy the interval.
     *
     * A source that is retrying uses [retryInterval] instead of its own interval; one whose
     * retry budget is spent falls back to the plain interval, which is what keeps it on the
     * schedule rather than off it (#659).
     *
     * @param now the reference timestamp for the current tick (captured once per tick
     *   for consistency across all sources).
     */
    internal fun isDue(
        source: EventSourceEntity,
        now: Instant
    ): Boolean {
        // Sources with no import history or in IDLE status (e.g. after manual retry) are always due.
        // IDLE check allows retry() to trigger immediate pickup without clearing lastImportAt,
        // preserving the historical record of when the last import ran.
        val lastImport = source.lastImportAt
        if (lastImport == null || source.status == ImportStatus.IDLE.name) return true

        val baseInterval = Duration.ofMinutes(source.importIntervalMinutes.toLong())
        val isRetrying =
            source.status == ImportStatus.FAILED.name &&
                source.retryCount > 0 &&
                source.retryCount < source.maxRetries
        val effectiveInterval = if (isRetrying) retryInterval(baseInterval, source.retryCount) else baseInterval

        return now.isAfter(lastImport.plus(effectiveInterval))
    }

    /**
     * How long to wait before the next attempt at a source that is retrying.
     *
     * The interval doubles per consecutive failure — and is then capped at [MAX_RETRY_INTERVAL],
     * which is the part [#659](https://github.com/enorm-labs/event-junkie/issues/659) added.
     *
     * **Doubling alone assumes a base interval measured in minutes.** Applied to the daily
     * default it produces a "retry" that waits *longer* than the healthy cadence — 1440 min
     * doubles to 48 h, then 96 h, then 192 h — so a failed source is attempted less often than
     * a working one, which inverts what a retry is for. `loge` failed on 2026-08-21 11:54 and
     * was next attempted on 2026-08-23 11:55, and that is the whole of the 47-hour gap.
     *
     * The cap makes the guarantee interval-independent: whatever a source's own schedule, a
     * failure is retried within [MAX_RETRY_INTERVAL]. Sub-cap intervals keep their backoff
     * unchanged — an hourly source still waits 2 h, then 4 h.
     */
    private fun retryInterval(
        baseInterval: Duration,
        retryCount: Int
    ): Duration {
        val backoffMultiplier = 2.0.pow(retryCount.coerceAtMost(MAX_BACKOFF_EXPONENT)).toLong()
        return minOf(baseInterval.multipliedBy(backoffMultiplier), MAX_RETRY_INTERVAL)
    }

    /**
     * Resets sources stuck in RUNNING status to FAILED.
     *
     * This guards against imports that never completed (e.g. due to application crash
     * or network timeout without proper error handling). Sources stuck for longer than
     * [stalenessTimeout] (configurable via `app.scheduling.staleness-timeout`, default: 30m)
     * are considered stale.
     */
    private suspend fun resetStuckSources() {
        val stalenessCutoff = Instant.now(clock).minus(stalenessTimeout)
        val stuckSources = eventSourceRepository.findStuckSources(stalenessCutoff).toList()

        for (source in stuckSources) {
            logger.warn { "Resetting stuck source '${source.slug}' from RUNNING to FAILED (last import: ${source.lastImportAt})" }
            try {
                eventSourceRepository.save(
                    source.copy(
                        status = ImportStatus.FAILED.name,
                        lastError = "Import timed out (stuck in RUNNING for >${stalenessTimeout.toMinutes()} minutes)",
                        retryCount = source.retryCount + 1
                    )
                )
            } catch (e: OptimisticLockingFailureException) {
                // The source was concurrently updated (e.g. the import just finished),
                // so it's no longer stuck — safe to skip. The next tick will re-evaluate.
                logger.info(e) { "Skipping stuck-source reset for '${source.slug}': version conflict indicates concurrent update" }
            }
        }
    }

    companion object {
        /**
         * Longest a retrying source may wait before its next attempt, whatever its own interval.
         *
         * Six hours fits all three of a daily source's retries inside the day it failed —
         * +6 h, +12 h, +18 h — so recovery happens within the cycle rather than across the
         * following week. See [retryInterval].
         */
        private val MAX_RETRY_INTERVAL: Duration = Duration.ofHours(6)

        /**
         * Maximum exponent for backoff, so `2^retryCount` cannot overflow a [Duration] before
         * [MAX_RETRY_INTERVAL] gets to cap it. `maxRetries` is operator-configurable, so this
         * is not bounded by the default of 3.
         */
        private const val MAX_BACKOFF_EXPONENT = 6

        /** Default staleness timeout: sources stuck in RUNNING for longer than this are reset to FAILED. */
        private val DEFAULT_STALENESS_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
