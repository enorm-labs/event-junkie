package de.norm.events.scraper

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface EventSourceRepository : CoroutineCrudRepository<EventSourceEntity, Long> {
    /** Finds an event source by its unique slug (used for API dispatch). */
    suspend fun findBySlug(slug: String): EventSourceEntity?

    fun findAllBy(pageable: Pageable): Flow<EventSourceEntity>

    /** Returns all enabled event sources for batch importing. */
    fun findByEnabledTrue(): Flow<EventSourceEntity>

    /**
     * How many sources are in [status] now, the `importer.source.running` gauge (#415). `RUNNING` is
     * the value worth alerting on: a restart mid-import strands a source in `RUNNING` forever, and a
     * count above zero across a quiet period is the tell.
     */
    suspend fun countByStatus(status: String): Long

    /**
     * Finds enabled sources that are candidates for import: the coarse SQL half, excluding disabled,
     * RUNNING and MISCONFIGURED, with `last_import_at < :now` deliberately broad so
     * [ScheduledImportService.isDue] can apply per-source interval and backoff in Kotlin. Raw SQL
     * because R2DBC has no derived queries with date arithmetic (ADR-002).
     *
     * A spent retry budget is not an exclusion (#659): a source that stops being attempted looks
     * identical to one with nothing to do, and `ej-importer-stale` and #700's gauge read the row.
     * Exhausting the budget only ends the shortened cadence.
     *
     * @param now the current timestamp.
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
     * Raises the data-quality flag on a source (#472). A targeted `UPDATE` without touching
     * `version`: this runs inside a completed import, immediately before that run's own
     * `markSuccess`, and bumping the version would fail optimistic locking and turn a successful
     * import into a spurious retry. It writes two columns nothing else writes.
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
     * Clears the flag once a run looks normal. Not optional: a flag only ever set is permanently on
     * within a month.
     */
    @Modifying
    @Query("UPDATE $EVENTS_SCHEMA.event_source SET flagged_at = NULL, flag_reason = NULL WHERE id = :id")
    suspend fun clearFlag(id: Long): Int

    /**
     * Sources stuck RUNNING past the staleness timeout, reset to FAILED by the scheduler.
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
     * Atomically claims a source for a run, moving it to RUNNING only if it is not already and the
     * row still carries [expectedVersion]; `1` on success, `0` otherwise. [startedAt] becomes
     * `last_import_at`. Testing the status in Kotlin and saving cannot serialize two callers:
     * sources queue on a bounded semaphore, still IDLE and visible to [findDueForImport], so a
     * scheduler tick starts a second run and both collide on `event_slug_key`. Optimistic locking
     * hides it: [EventImportService.saveWithVersionConflictRetry] re-fetches and retries, so the
     * second `markRunning` succeeds. A conditional `UPDATE … WHERE status <> 'RUNNING'` is decided
     * by the database.
     *
     * [expectedVersion] makes it a compare-and-swap against the row the caller read: without it a
     * tick that read a source while IDLE and reaches its claim after a manual trigger imported it
     * would find SUCCESS and re-scrape. Any completed run bumps the version, and the caller's
     * `version + 1` stays exact for the closing save.
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
     * Bulk-resets all enabled, failed or misconfigured sources to IDLE in one UPDATE, preserving
     * `last_import_at`. Bypasses `@Version` but increments `version`, so a concurrent `findBySlug` +
     * `save` that loaded the entity before this cannot overwrite the reset.
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
