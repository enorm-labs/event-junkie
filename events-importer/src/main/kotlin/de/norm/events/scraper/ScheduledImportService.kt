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
 * Periodic scheduler: a tick every 60 seconds finds due sources and delegates each to
 * [EventImportService.importFromSource]. Per-source `importIntervalMinutes`; retry with capped
 * exponential backoff, never exceeding six hours, and a source past its budget returns to its
 * normal interval rather than leaving the schedule; sources stuck RUNNING for >30 min reset to
 * FAILED; RUNNING and MISCONFIGURED sources are skipped.
 *
 * `app.scheduling.enabled=false` disables it. The `@ConditionalOnProperty` below is belt and
 * braces beside [de.norm.events.SchedulingConfiguration]; it stays because removing the bean is
 * what keeps a test from reaching it by accident.
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
     * Main tick, every 60 seconds. Spring Framework 7 supports `suspend` in `@Scheduled`, so no
     * `runBlocking`. `$$"..."` raises the interpolation threshold so `${...}` is literal.
     */
    @Scheduled(fixedDelayString = $$"${app.scheduling.tick-interval:60000}")
    suspend fun tick() {
        resetStuckSources()
        importDueSources()
    }

    /**
     * Imports all sources that are due: `lastImportAt` + `importIntervalMinutes` in the past, with
     * exponential backoff for failed ones.
     */
    private suspend fun importDueSources() {
        // One timestamp for the whole tick, so every source is evaluated against the same moment.
        val now = Instant.now(clock)
        val candidates = eventSourceRepository.findDueForImport(now).toList()

        // Filter to sources that are actually due based on their individual interval + backoff
        val dueSources = candidates.filter { isDue(it, now) }

        if (dueSources.isEmpty()) return

        logger.info { "Scheduler tick: ${dueSources.size} source(s) due for import" }

        // Concurrent execution is safe: per-host politeness is PerHostThrottlingFilter's, the artist
        // cache is per call, each source has its own transaction.
        eventImportService.importConcurrently(dueSources)
    }

    /**
     * Whether a source is due: never imported, or the interval has passed since the last. A
     * retrying source uses [retryInterval]; one whose budget is spent falls back to its plain
     * interval, which keeps it on the schedule (#659).
     *
     * @param now the tick's reference timestamp.
     */
    internal fun isDue(
        source: EventSourceEntity,
        now: Instant
    ): Boolean {
        // No history or IDLE (after a manual retry) is always due, so retry() triggers immediate pickup
        // without clearing lastImportAt.
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
     * How long a retrying source waits: doubling per consecutive failure, capped at
     * [MAX_RETRY_INTERVAL] (#659). Doubling alone assumes a base interval in minutes; on the daily
     * default it waits longer than the healthy cadence (48 h, 96 h, 192 h), and a daily source that
     * failed went 47 hours before its next attempt. The cap makes the guarantee
     * interval-independent; sub-cap intervals keep their backoff.
     */
    private fun retryInterval(
        baseInterval: Duration,
        retryCount: Int
    ): Duration {
        val backoffMultiplier = 2.0.pow(retryCount.coerceAtMost(MAX_BACKOFF_EXPONENT)).toLong()
        return minOf(baseInterval.multipliedBy(backoffMultiplier), MAX_RETRY_INTERVAL)
    }

    /**
     * Resets sources stuck in RUNNING to FAILED, for imports that never completed (a crash, a
     * timeout without handling). Stale past [stalenessTimeout]
     * (`app.scheduling.staleness-timeout`, default 30m).
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
                        // No exception to classify: a stuck row is a crash or a rollout, not a venue's answer (#708).
                        lastFailureReason = null,
                        retryCount = source.retryCount + 1
                    )
                )
            } catch (e: OptimisticLockingFailureException) {
                // Concurrently updated, so no longer stuck; the next tick re-evaluates.
                logger.info(e) { "Skipping stuck-source reset for '${source.slug}': version conflict indicates concurrent update" }
            }
        }
    }

    companion object {
        /**
         * Longest a retrying source may wait. Six hours fits all three of a daily source's retries inside
         * the day it failed (+6 h, +12 h, +18 h). See [retryInterval].
         */
        private val MAX_RETRY_INTERVAL: Duration = Duration.ofHours(6)

        /**
         * Maximum exponent, so `2^retryCount` cannot overflow a [Duration] before the cap applies;
         * `maxRetries` is operator-configurable.
         */
        private const val MAX_BACKOFF_EXPONENT = 6

        /** Default staleness timeout: sources stuck in RUNNING for longer than this are reset to FAILED. */
        private val DEFAULT_STALENESS_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
