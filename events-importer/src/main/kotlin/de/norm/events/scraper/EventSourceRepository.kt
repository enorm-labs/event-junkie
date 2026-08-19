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
     * 1. **SQL (coarse)**: filters out sources that are definitely not due — disabled,
     *    already running, or exhausted retries. The `last_import_at < :now` clause is
     *    intentionally broad (always true for past timestamps) to keep candidates that
     *    need per-source interval evaluation.
     * 2. **Kotlin (precise)**: [ScheduledImportService.isDue] applies per-source interval
     *    and exponential backoff logic that cannot be expressed in a single SQL query
     *    (each source has its own `importIntervalMinutes` and `retryCount`).
     *
     * Sources with status = 'RUNNING' are excluded to prevent overlapping imports.
     * Sources with status = 'MISCONFIGURED' are excluded because they need manual intervention.
     * Failed sources that have exhausted their retry budget (`retry_count >= max_retries`)
     * are also excluded to avoid fetching rows that will always be skipped.
     * Raw SQL is required because R2DBC does not support derived queries with
     * date arithmetic (see ADR-002).
     *
     * @param now the current timestamp, used as a coarse upper bound on `last_import_at`.
     */
    @Query(
        """
        SELECT * FROM $EVENTS_SCHEMA.event_source
        WHERE enabled = true
          AND status NOT IN ('${ImportStatus.S_RUNNING}', '${ImportStatus.S_MISCONFIGURED}')
          AND (status != '${ImportStatus.S_FAILED}' OR retry_count < max_retries)
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
     * Atomically claims a source for an import run, moving it to RUNNING **only if it is not
     * already running**.
     *
     * This is the serialization point that keeps two import runs off the same source. Testing
     * the status in Kotlin and then saving cannot do it: every caller reaches
     * [EventImportService.importFromSource] through a bounded semaphore, so a source can sit
     * queued — still IDLE, still `last_import_at IS NULL`, and therefore still visible to
     * [findDueForImport] — long after its import was requested. A scheduler tick landing in
     * that window starts a second run of the same source, and both then scrape and insert the
     * same events, which collides on the `event_slug_key` unique index.
     *
     * Optimistic locking does not help here, and in fact hides the problem: two writers each
     * calling `save()` produce a version conflict that
     * [EventImportService.saveWithVersionConflictRetry] resolves by re-fetching and retrying,
     * so the second `markRunning` succeeds and the duplicate run proceeds. A conditional
     * `UPDATE … WHERE status <> 'RUNNING'` is decided by the database instead: exactly one
     * caller updates a row, and the loser sees `0`.
     *
     * `version` is incremented for the same reason [resetAllFailedToIdle] does it — so a
     * concurrent read-modify-write holding a stale entity cannot silently overwrite the claim.
     * The caller must account for that increment on the entity it goes on to save.
     *
     * The claim is also gated on [expectedVersion], which makes it a compare-and-swap against
     * the exact row the caller read rather than merely "whatever is not RUNNING right now".
     * Without that gate the guard only excludes runs that *overlap*, not one that starts the
     * moment another finishes — and imports queue on a bounded semaphore, so a source can wait
     * a long time between being read and being claimed. A scheduler tick that reads a source
     * while it is still IDLE, and reaches its claim after a manual trigger has already imported
     * it, would find the status back at SUCCESS and re-scrape the whole source. Comparing the
     * version closes that window: any completed run bumps it, so the stale caller sees `0`.
     *
     * Gating on the version additionally makes the caller's `version + 1` exact, so the entity
     * returned by [EventImportService.claimForImport] carries the version actually written and
     * the closing `markSuccess`/`markFailed` save no longer trips optimistic locking.
     *
     * @param id the source to claim.
     * @param expectedVersion the `version` the caller read; the claim fails if the row moved on.
     * @param startedAt the claim timestamp, recorded as `last_import_at` for staleness detection.
     * @return `1` when the claim succeeded, `0` when another run holds it or the row has changed.
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
