package de.norm.events.scraper

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * Reactive repository for [EventSourceEntity] persistence via R2DBC.
 */
interface EventSourceRepository : CoroutineCrudRepository<EventSourceEntity, Long> {
    /** Finds an event source by its unique slug (used for API dispatch). */
    suspend fun findBySlug(slug: String): EventSourceEntity?

    /** Returns all event sources with pagination and sorting support. */
    fun findAllBy(pageable: Pageable): Flow<EventSourceEntity>

    /** Returns all enabled event sources for batch importing. */
    fun findByEnabledTrue(): Flow<EventSourceEntity>

    /**
     * How many sources are in [status] right now — the `importer.source.running` gauge (#415).
     *
     * The value worth alerting on is `RUNNING`, because ADR-008's staleness guard only runs under
     * the scheduler: a restart mid-import strands a source in `RUNNING` forever, and nothing about
     * that looks wrong from outside. A count that stays above zero across a quiet period is the tell.
     */
    suspend fun countByStatus(status: String): Long

    /**
     * Finds enabled sources that are candidates for import.
     *
     * This is the first phase of a two-phase filtering strategy:
     * 1. **SQL (coarse)**: filters out sources that are definitely not due — disabled or
     *    already running. The `last_import_at < :now` clause is intentionally broad (always
     *    true for past timestamps) to keep candidates that need per-source interval evaluation.
     * 2. **Kotlin (precise)**: [ScheduledImportService.isDue] applies per-source interval
     *    and capped backoff logic that cannot be expressed in a single SQL query
     *    (each source has its own `importIntervalMinutes` and `retryCount`).
     *
     * Sources with status = 'RUNNING' are excluded to prevent overlapping imports.
     * Sources with status = 'MISCONFIGURED' are excluded because they need manual intervention.
     * Raw SQL is required because R2DBC does not support derived queries with
     * date arithmetic (see ADR-002).
     *
     * **A spent retry budget is not an exclusion** (#659). A persistently broken source stays on the
     * schedule, because the one failure the importer cannot express as *absence* is a source that
     * stops being attempted: it looks identical to one with nothing to do, and both
     * `ej-importer-stale` and #700's per-source gauge read the row rather than the schedule.
     * Exhausting the budget only ends the shortened retry cadence — [ScheduledImportService.isDue]
     * puts the source back on its own interval, so it keeps failing visibly.
     *
     * @param now the current timestamp, used as a coarse upper bound on `last_import_at`.
     */
    @Query(
        """
        SELECT * FROM $EVENTS_SCHEMA.event_source
        WHERE enabled = true
          AND status NOT IN ('${ImportStatus.S_RUNNING}', '${ImportStatus.S_MISCONFIGURED}')
          AND (
              last_import_at IS NULL
              OR last_import_at < :now
          )
        """
    )
    fun findDueForImport(now: Instant): Flow<EventSourceEntity>

    /**
     * Raises the data-quality flag on a source (#472).
     *
     * A targeted `UPDATE` rather than a `save()`, and **deliberately without touching `version`**:
     * this runs inside a completed import, immediately before that run's own `markSuccess` writes
     * the entity it has been holding. Bumping the version here would make that save fail optimistic
     * locking and turn a successful import into a spurious retry — the flag would cost exactly the
     * thing it is meant to observe.
     *
     * It writes two columns nothing else writes, so there is no lost update to worry about.
     */
    @Modifying
    @Query(
        """
        UPDATE $EVENTS_SCHEMA.event_source
        SET flagged_at = :flaggedAt, flag_reason = :reason
        WHERE id = :id
        """
    )
    suspend fun setFlag(
        id: Long,
        flaggedAt: Instant,
        reason: String
    ): Int

    /**
     * Clears the flag once a run looks normal again.
     *
     * Not optional: a flag that is only ever set is permanently on within a month, and then it is
     * decoration. The clear is what makes the set mean something.
     */
    @Modifying
    @Query("UPDATE $EVENTS_SCHEMA.event_source SET flagged_at = NULL, flag_reason = NULL WHERE id = :id")
    suspend fun clearFlag(id: Long): Int

    /**
     * Finds sources stuck in RUNNING status for longer than the staleness timeout.
     *
     * These are reset to FAILED by the scheduler to prevent permanently stuck imports
     * (e.g. due to an application crash during import).
     */
    @Query(
        """
        SELECT * FROM $EVENTS_SCHEMA.event_source
        WHERE status = '${ImportStatus.S_RUNNING}'
          AND last_import_at IS NOT NULL
          AND last_import_at < :stalenessCutoff
        """
    )
    fun findStuckSources(stalenessCutoff: Instant): Flow<EventSourceEntity>

    /**
     * Atomically claims a source for an import run, moving it to RUNNING **only if it is not already
     * running** and the row still carries [expectedVersion]. Returns `1` on success, `0` when another
     * run holds it or the row has moved on. [startedAt] is recorded as `last_import_at`.
     *
     * This is the serialization point that keeps two import runs off the same source. Testing the
     * status in Kotlin and then saving cannot do it: callers reach
     * [EventImportService.importFromSource] through a bounded semaphore, so a source can sit queued —
     * still IDLE, still visible to [findDueForImport] — long after its import was requested. A
     * scheduler tick landing in that window starts a second run, and both insert the same events,
     * colliding on the `event_slug_key` unique index.
     *
     * Optimistic locking does not help, and in fact hides it: two writers each calling `save()`
     * produce a conflict that [EventImportService.saveWithVersionConflictRetry] resolves by
     * re-fetching and retrying, so the second `markRunning` succeeds and the duplicate run proceeds.
     * A conditional `UPDATE … WHERE status <> 'RUNNING'` is decided by the database: exactly one
     * caller updates the row, and the loser sees `0`.
     *
     * **[expectedVersion] makes it a compare-and-swap** against the row the caller read, not against
     * "whatever is not RUNNING now". Without it the guard excludes only *overlapping* runs, not one
     * starting as another finishes: a tick that read a source while it was IDLE and reaches its claim
     * after a manual trigger already imported it would find SUCCESS and re-scrape everything. Any
     * completed run bumps the version, so the stale caller sees `0` — and the caller's `version + 1`
     * stays exact, so the closing `markSuccess`/`markFailed` save does not trip optimistic locking.
     */
    @Modifying
    @Query(
        """
        UPDATE $EVENTS_SCHEMA.event_source
        SET status = '${ImportStatus.S_RUNNING}', last_error = NULL, last_import_at = :startedAt, version = version + 1
        WHERE id = :id AND version = :expectedVersion AND status <> '${ImportStatus.S_RUNNING}'
        """
    )
    suspend fun claimForImport(
        id: Long,
        expectedVersion: Long,
        startedAt: Instant
    ): Int

    /**
     * Bulk-resets all enabled, failed or misconfigured event sources to IDLE for retry.
     *
     * Uses a single UPDATE statement instead of fetch-modify-save per source
     * for better performance when many sources need to be retried.
     * Preserves `last_import_at` as a historical record — [ScheduledImportService.isDue]
     * treats IDLE sources as always-due regardless of when they last ran.
     * Also resets MISCONFIGURED sources so they can be retried after fixing their configuration.
     *
     * Note: This bypasses `@Version` optimistic locking but increments `version` to prevent
     * stale read-modify-write cycles from silently succeeding. Without this increment, a
     * concurrent `findBySlug` + `save` that loaded the entity before this bulk update would
     * still see a matching `version` and overwrite the reset.
     *
     * @return the number of rows updated.
     */
    @Modifying
    @Query(
        """
        UPDATE $EVENTS_SCHEMA.event_source
        SET status = '${ImportStatus.S_IDLE}', retry_count = 0, last_error = NULL, version = version + 1
        WHERE enabled = true AND status IN ('${ImportStatus.S_FAILED}', '${ImportStatus.S_MISCONFIGURED}')
        """
    )
    suspend fun resetAllFailedToIdle(): Int
}
