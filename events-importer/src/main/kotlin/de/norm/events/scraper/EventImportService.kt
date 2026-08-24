package de.norm.events.scraper

import de.norm.events.venue.VenueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Orchestrates the event import pipeline: delegate to importer → upsert → cleanup.
 *
 * For each enabled [EventSourceEntity], the service:
 * 1. Resolves the matching [EventImporter] by [EventSource] enum.
 * 2. Delegates fetching and parsing to the importer.
 * 3. Delegates persistence (upsert, artist resolution, stale cleanup) to [EventUpsertService].
 * 4. Updates the event source metadata (status, event count, ETag, etc.).
 */
@Service
class EventImportService(
    private val eventSourceRepository: EventSourceRepository,
    private val eventUpsertService: EventUpsertService,
    private val eventImporters: List<EventImporter>,
    private val venueRepository: VenueRepository,
    /** Programmatic transaction control — used instead of @Transactional to avoid self-invocation issues. */
    private val transactionalOperator: TransactionalOperator,
    /** Every meter this pipeline publishes (#415). See [ImporterMetrics] for why the names are an interface. */
    private val metrics: ImporterMetrics,
    /** Per-field coverage against each source's own history (#472) — the partial-failure alarm. */
    private val fieldCoverageService: FieldCoverageService,
    /** Injected clock for deterministic time in tests. Defaults to system UTC clock in production. */
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Maximum number of event sources imported concurrently. Each source runs in its
     * own coroutine, bounded by a [Semaphore] to limit database and network pressure.
     * Per-host politeness is already enforced by [PerHostThrottlingFilter], so sources
     * targeting different hosts benefit from true concurrency while same-host sources
     * are naturally serialized at the HTTP layer.
     */
    @Value($$"${app.import.max-concurrency:4}")
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Global permit pool bounding the number of in-flight source imports.
     *
     * Because this service is a singleton and **every** import path funnels through
     * [importFromSource] — the scheduler's [importConcurrently], [importBySlug], and both
     * fire-and-forget triggers in `ImportJobLauncher` — acquiring a permit here caps total
     * concurrency across *all* callers. A burst of manual admin triggers can no longer stack
     * on top of a scheduled tick and overwhelm the R2DBC connection pool.
     */
    private val importSemaphore = Semaphore(maxConcurrency)

    /** Index of event importers by their event source for O(1) dispatch. */
    private val importersBySource: Map<EventSource, EventImporter> by lazy {
        eventImporters.associateBy { it.eventSource }
    }

    /**
     * Imports events from all enabled event sources.
     *
     * Sources are imported concurrently (up to [maxConcurrency] at a time).
     * Each source is processed independently — a failure in one source does not
     * prevent other sources from being imported.
     */
    suspend fun importAll(): List<ImportResultResponse> {
        val sources = eventSourceRepository.findByEnabledTrue().toList()
        logger.info { "Starting import for ${sources.size} enabled source(s)" }
        return importConcurrently(sources)
    }

    /**
     * Imports events from a single event source identified by [slug].
     *
     * @throws EventSourceNotFoundException if no source with the given slug exists.
     */
    suspend fun importBySlug(slug: String): ImportResultResponse {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        return importFromSource(source)
    }

    /**
     * Imports multiple sources concurrently, bounded by [maxConcurrency].
     *
     * Each source runs in its own coroutine; the shared [importSemaphore] acquired inside
     * [importFromSource] limits how many execute simultaneously (globally, not just within
     * this batch). This is safe because:
     * - The artist cache in [EventUpsertService] is local to each [importFromSource] call.
     * - Concurrent artist creation is handled via [DataIntegrityViolationException] fallback.
     * - Per-host HTTP politeness is enforced by [PerHostThrottlingFilter].
     * - Each source's upsert runs in its own transaction.
     */
    internal suspend fun importConcurrently(sources: List<EventSourceEntity>): List<ImportResultResponse> =
        coroutineScope {
            sources
                .map { source ->
                    async {
                        logger.info { "Importing source '${source.slug}' (interval=${source.importIntervalMinutes}min, retries=${source.retryCount})" }
                        importFromSource(source)
                    }
                }.awaitAll()
        }

    /**
     * Core import pipeline for a single source.
     *
     * Handles the full lifecycle: delegate to importer → upsert → update metadata.
     * Errors are caught and recorded on the event source rather than propagated.
     *
     * Status updates (the [claimForImport] claim, markSuccess/markFailed) run outside the
     * transactional boundary so they always commit, even if a DB error during
     * upsert marks the transaction as rollback-only.
     *
     * The run begins by **claiming** the source (RUNNING only if not already RUNNING). A
     * source another run already holds is skipped, returning an un-imported result — this is
     * what keeps two callers from scraping and upserting the same source at once.
     *
     * **Precondition**: The [source] must be a persisted entity fetched from the repository.
     * This method manages the source's import lifecycle status (RUNNING → SUCCESS/FAILED),
     * so callers must not manipulate the source's status independently.
     *
     * Acquires a permit from the global [importSemaphore] for the full duration of the import,
     * so the total number of concurrent imports never exceeds [maxConcurrency] regardless of the
     * caller. The (fail-fast) persisted-id precondition is checked *before* taking a permit.
     */
    internal suspend fun importFromSource(source: EventSourceEntity): ImportResultResponse {
        requireNotNull(source.id) { "Event source must be persisted (have a non-null id) before importing" }
        return importSemaphore.withPermit { timedImportPipeline(source) }
    }

    /**
     * Wraps [runImportPipeline] with the run timer and the outcome counter (#415).
     *
     * The outcome is set at each exit rather than derived from the returned [ImportResultResponse],
     * because the response cannot distinguish them: a not-modified run, a run skipped because
     * another already held the claim, and a misconfigured source all come back as
     * `imported=false, eventCount=0`, and two of those carry an error while meaning very different
     * things. Deriving the tag from the response would quietly merge the states that matter.
     *
     * `finally` rather than a call on each path, so a run always records exactly once — including
     * one that throws past every branch below.
     */
    private suspend fun timedImportPipeline(source: EventSourceEntity): ImportResultResponse {
        val startedAt = System.nanoTime()
        // FAILED rather than a nullable: if an exception escapes every branch, "the run failed" is
        // the honest reading, and a missing sample would be indistinguishable from a run that never
        // started.
        var outcome = ImporterMetrics.RunOutcome.FAILED
        try {
            val (response, runOutcome) = runImportPipeline(source)
            outcome = runOutcome
            return response
        } finally {
            metrics.recordRun(source.slug, outcome, (System.nanoTime() - startedAt).nanoseconds)
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Intentional: record any failure; multiple early returns for error paths
    private suspend fun runImportPipeline(source: EventSourceEntity): Pair<ImportResultResponse, ImporterMetrics.RunOutcome> {
        val eventSourceEnum =
            try {
                EventSource.valueOf(source.sourceType)
            } catch (_: IllegalArgumentException) {
                val error = "Unknown source type '${source.sourceType}'"
                logger.error { error }
                // Configuration error — will never self-resolve on retry, so mark as MISCONFIGURED
                // instead of FAILED to avoid consuming retry budget (see review issue #1).
                markMisconfigured(source, error)
                return ImportResultResponse(sourceSlug = source.slug, imported = false, eventCount = 0, error = error) to
                    ImporterMetrics.RunOutcome.MISCONFIGURED
            }

        val importer = importersBySource[eventSourceEnum]
        if (importer == null) {
            val error = "No importer registered for source type '${source.sourceType}'"
            logger.error { error }
            // Configuration error — no importer is deployed for this source type.
            markMisconfigured(source, error)
            return ImportResultResponse(sourceSlug = source.slug, imported = false, eventCount = 0, error = error) to
                ImporterMetrics.RunOutcome.MISCONFIGURED
        }

        val runningSource =
            claimForImport(source)
                ?: return ImportResultResponse(sourceSlug = source.slug, imported = false, eventCount = 0) to
                    ImporterMetrics.RunOutcome.SKIPPED

        return try {
            when (val result = importer.importEvents(runningSource.url, runningSource.etag, runningSource.lastModified)) {
                is ImportResult.NotModified -> {
                    logger.info { "Source '${runningSource.slug}' not modified, skipping import" }
                    // The count carries forward rather than resetting to 0 (#659). A 304 says the
                    // listing has not changed, so the number of events it holds has not changed
                    // either — the count from the run that last read it is still the true one.
                    // Writing 0 here made an unchanged source indistinguishable from an emptied
                    // one in the exact column an operator reaches for: `loge` reported
                    // `lastEventCount = 0` on a run that succeeded, and it had six events all along.
                    markSuccess(runningSource, runningSource.lastEventCount)
                    ImportResultResponse(sourceSlug = runningSource.slug, imported = false, eventCount = 0) to
                        ImporterMetrics.RunOutcome.NOT_MODIFIED
                }

                is ImportResult.Success -> {
                    logger.info { "Scraped ${result.events.size} event(s) from '${runningSource.slug}'" }

                    // Look up the venue slug for inclusion in event slugs (ensures cross-venue uniqueness).
                    val venue =
                        venueRepository.findById(runningSource.venueId)
                            ?: error("Venue with id ${runningSource.venueId} not found for source '${runningSource.slug}'")

                    // Wrap upserts and cleanup in a transaction so partial failures roll back cleanly.
                    // Uses TransactionalOperator instead of @Transactional to keep status updates
                    // (the claim, markSuccess/markFailed) outside the transaction boundary —
                    // they must always commit even if the upsert transaction rolls back.
                    val upsert =
                        transactionalOperator.executeAndAwait {
                            val sourceId = requireNotNull(runningSource.id) { "Event source must be persisted before importing" }
                            eventUpsertService.upsertAndCleanup(result.events, runningSource.venueId, venue.slug, sourceId)
                        }

                    // After the transaction commits, deliberately: a counter incremented for writes
                    // that then rolled back would overstate what is in the database, and there is no
                    // way to take an increment back.
                    metrics.recordEventsWritten(runningSource.slug, ImporterMetrics.WriteOperation.INSERTED, upsert.inserted)
                    metrics.recordEventsWritten(runningSource.slug, ImporterMetrics.WriteOperation.UPDATED, upsert.updated)
                    metrics.recordEventsWritten(runningSource.slug, ImporterMetrics.WriteOperation.SKIPPED, upsert.skipped)

                    // Per-field coverage, BEFORE markSuccess and after the transaction (#472).
                    //
                    // Measured from `result.events` — what the scraper extracted — and not from the
                    // rows in the database, which also hold everything previous runs wrote. A
                    // selector that stopped matching shows up in the former and is invisible in the
                    // latter until the old rows age out.
                    //
                    // Before `markSuccess` because that save carries the entity this run has been
                    // holding: the flag is written by a targeted UPDATE that does not touch
                    // `version`, so the ordering is safe either way, and doing it first means a
                    // flagged run is flagged even if the closing save is retried.
                    //
                    // Unguarded here on purpose: `record` never throws, and owning that promise in
                    // the service rather than at each call site is what stops the next caller
                    // forgetting it.
                    fieldCoverageService.record(runningSource, result.events)

                    markSuccess(runningSource, upsert.total, result.etag, result.lastModified)
                    ImportResultResponse(sourceSlug = runningSource.slug, imported = true, eventCount = upsert.total) to
                        ImporterMetrics.RunOutcome.SUCCESS
                }
            }
        } catch (e: Exception) {
            val error = e.message ?: "Unknown error during import"
            logger.error(e) { "Import failed for source '${runningSource.slug}': $error" }
            metrics.recordScrapeFailure(runningSource.slug, scrapeFailureReason(e))
            markFailed(runningSource, error)
            ImportResultResponse(sourceSlug = runningSource.slug, imported = false, eventCount = 0, error = error) to
                ImporterMetrics.RunOutcome.FAILED
        }
    }

    // -- Event source status management --

    /**
     * Claims [source] for this run by moving it to RUNNING, returning the claimed entity — or
     * `null` when another run already holds the claim, in which case the caller must not import.
     *
     * The claim is a conditional UPDATE decided by the database
     * ([EventSourceRepository.claimForImport]), because a read-then-save in Kotlin cannot
     * serialize two callers: imports queue on [importSemaphore] before reaching this point, so
     * the same source can be requested twice and stay visibly un-started for the whole wait.
     *
     * The claim is gated on the version [source] was read at, so it fails not only when another
     * run currently holds the source but also when the row changed at all since the read. That
     * covers the case the status check alone misses: imports queue on [importSemaphore], so a
     * scheduler tick can read a source while it is still IDLE and only reach its claim after a
     * manual trigger has already imported it — by which point the status is SUCCESS again and a
     * status-only guard would wave the duplicate run through, re-scraping the venue.
     *
     * The returned entity mirrors the claim in memory rather than re-reading the row. The
     * conditional UPDATE touches exactly the four columns reproduced here, so the copy matches
     * what was written, and this keeps the claim to a single round trip. Because the claim only
     * succeeds when the row was still at [EventSourceEntity.version], `version + 1` is the value
     * actually persisted rather than a guess, so the closing `markSuccess`/`markFailed` save
     * matches on the first attempt.
     *
     * A source left in RUNNING by a crashed run blocks its own next import until
     * [ScheduledImportService.resetStuckSources] releases it (default: 30 minutes). That is
     * the guard the scheduler already relied on — it now also covers a manual trigger, which
     * previously ignored a RUNNING source and started a second, overlapping run.
     */
    private suspend fun claimForImport(source: EventSourceEntity): EventSourceEntity? {
        val sourceId = requireNotNull(source.id) { "Cannot claim an unpersisted event source" }
        val expectedVersion = requireNotNull(source.version) { "Cannot claim an event source without a version" }
        val claimedAt = Instant.now(clock)
        if (eventSourceRepository.claimForImport(sourceId, expectedVersion, claimedAt) == 0) {
            logger.info { "Skipping import of '${source.slug}': another run holds it or it has been imported since" }
            return null
        }
        return source.copy(
            status = ImportStatus.RUNNING.name,
            lastError = null,
            // Records when the import started, for the scheduler's staleness detection.
            lastImportAt = claimedAt,
            version = expectedVersion + 1
        )
    }

    /**
     * Closes a run that worked, advancing both timestamps.
     *
     * `lastSuccessAt` is written **only here**, which is what makes it mean what it says.
     * `lastImportAt` is written by this, by [markFailed] and by the claim, so it is a last-*attempt*
     * time; the pair is what lets `importer.source.last_success` stay correct across a failure
     * instead of disappearing (#415). Every caller of this method is a run that reached the source
     * and got an answer — including a 304, which is a working scraper and not a skipped one.
     *
     * @param eventCount how many events the run read, or `null` for a run that did not read the
     *   listing at all (a 304), which carries the previous count forward rather than zeroing it.
     *   `null` also survives to the column on the one run where it is the honest answer: a source
     *   whose very first attempt is a 304, which cannot happen without an `etag` from an earlier one.
     */
    private suspend fun markSuccess(
        source: EventSourceEntity,
        eventCount: Int?,
        newEtag: String? = source.etag,
        newLastModified: String? = source.lastModified
    ): EventSourceEntity =
        saveWithVersionConflictRetry(source) {
            val now = Instant.now(clock)
            it.copy(
                status = ImportStatus.SUCCESS.name,
                lastImportAt = now,
                lastSuccessAt = now,
                lastEventCount = eventCount,
                lastError = null,
                etag = newEtag,
                lastModified = newLastModified,
                retryCount = 0
            )
        }

    private suspend fun markFailed(
        source: EventSourceEntity,
        error: String
    ): EventSourceEntity =
        saveWithVersionConflictRetry(source) {
            it.copy(
                status = ImportStatus.FAILED.name,
                lastImportAt = Instant.now(clock),
                lastError = error.take(MAX_ERROR_LENGTH),
                retryCount = it.retryCount + 1
            )
        }

    /**
     * Marks a source as misconfigured — a permanent configuration error that
     * will never self-resolve on retry (e.g. unknown source type, missing importer).
     *
     * Unlike [markFailed], this does NOT increment [EventSourceEntity.retryCount]
     * because retrying is pointless for configuration errors. The scheduler skips
     * MISCONFIGURED sources entirely, so they require manual intervention
     * (fix the config, then call retry to reset to IDLE).
     */
    private suspend fun markMisconfigured(
        source: EventSourceEntity,
        error: String
    ): EventSourceEntity =
        saveWithVersionConflictRetry(source) {
            it.copy(
                status = ImportStatus.MISCONFIGURED.name,
                lastImportAt = Instant.now(clock),
                lastError = error.take(MAX_ERROR_LENGTH)
            )
        }

    /**
     * Saves the [source] entity after applying [mutation], with a single retry on
     * [OptimisticLockingFailureException].
     *
     * An optimistic locking conflict can occur when an external writer (e.g.
     * [ScheduledImportService.resetStuckSources]) modifies the `event_source` row
     * between the [claimForImport] claim and `markSuccess`/`markFailed`, making the in-memory
     * `@Version` stale. This is a rare but possible race condition (see ADR-009).
     *
     * On conflict, the entity is re-fetched from the database to obtain the latest
     * version, the [mutation] is re-applied, and the save is retried once. If the
     * retry also fails, the exception propagates — the scheduler will pick up the
     * source on the next tick.
     */
    private suspend fun saveWithVersionConflictRetry(
        source: EventSourceEntity,
        mutation: (EventSourceEntity) -> EventSourceEntity
    ): EventSourceEntity =
        try {
            eventSourceRepository.save(mutation(source))
        } catch (e: OptimisticLockingFailureException) {
            val sourceId = requireNotNull(source.id) { "Cannot retry save for unpersisted event source" }
            logger.warn(e) { "Optimistic locking conflict for source '${source.slug}' (id=$sourceId), re-fetching and retrying" }
            val freshSource =
                eventSourceRepository.findById(sourceId)
                    ?: error("Event source '${source.slug}' (id=$sourceId) disappeared during retry")
            eventSourceRepository.save(mutation(freshSource))
        }

    companion object {
        /** Maximum length for error messages stored in the database. */
        private const val MAX_ERROR_LENGTH = 1000

        /** Default concurrency limit for parallel source imports. */
        internal const val DEFAULT_MAX_CONCURRENCY = 4
    }
}
