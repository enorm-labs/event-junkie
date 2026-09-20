package de.norm.events.scraper

import de.norm.events.licence.SourceLicences
import de.norm.events.venue.VenueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Orchestrates the import pipeline for each enabled [EventSourceEntity]: resolve the
 * [EventImporter] by [EventSource], delegate fetching and parsing, delegate persistence to
 * [EventUpsertService], update the source's metadata.
 */
@Service
@Suppress(
    // Constructor injection: one parameter per collaborator; splitting the service hides the wiring.
    "LongParameterList",
    // Twelve functions, eleven of them one named step of this pipeline; inlining `afterCommit` would
    // push a method past the LongMethod cap.
    "TooManyFunctions"
)
class EventImportService(
    private val eventSourceRepository: EventSourceRepository,
    private val eventUpsertService: EventUpsertService,
    private val eventImporters: List<EventImporter>,
    private val venueRepository: VenueRepository,
    /** Programmatic transaction control rather than @Transactional, to avoid self-invocation issues. */
    private val transactionalOperator: TransactionalOperator,
    /** Every meter this pipeline publishes (#415). See [ImporterMetrics] for why the names are an interface. */
    private val metrics: ImporterMetrics,
    /** Per-field coverage against each source's own history (#472) — the partial-failure alarm. */
    private val fieldCoverageService: FieldCoverageService,
    /** Fills in the missing language, for the sources whose grant allows it (ADR-026, #470). */
    private val descriptionTranslationService: DescriptionTranslationService,
    private val musicBrainzLookupService: MusicBrainzLookupService,
    /**
     * The `robots.txt` rules behind [RobotsTxtFilter], read again to record what they said about
     * this source's entry URL (#790). A map read, not a fetch: the filter has already read the file.
     */
    private val robotsRulesCache: RobotsRulesCache,
    /** Injected clock for deterministic time in tests. Defaults to system UTC clock in production. */
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Maximum number of sources imported concurrently, each in its own coroutine bounded by a
     * [Semaphore]. Per-host politeness is [PerHostThrottlingFilter]'s job.
     */
    @Value($$"${app.import.max-concurrency:4}")
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Global permit pool. Every import path funnels through [importFromSource], so this caps total
     * concurrency across all callers: a burst of manual admin triggers cannot stack on a scheduled
     * tick and overwhelm the R2DBC pool.
     */
    private val importSemaphore = Semaphore(maxConcurrency)

    /** Index of event importers by their event source for O(1) dispatch. */
    private val importersBySource: Map<EventSource, EventImporter> by lazy {
        eventImporters.associateBy { it.eventSource }
    }

    /**
     * Imports events from all enabled sources, concurrently up to [maxConcurrency]; a failure in one
     * does not prevent the others.
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
     * Imports multiple sources concurrently, bounded by the shared [importSemaphore] inside
     * [importFromSource]. Safe because the artist cache in [EventUpsertService] is per call,
     * concurrent artist creation falls back on [DataIntegrityViolationException], per-host politeness
     * is [PerHostThrottlingFilter]'s, and each source's upsert runs in its own transaction.
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
     * Core import pipeline for a single source. Errors are recorded on the source rather than
     * propagated. Status updates (the claim, markSuccess/markFailed) run outside the transaction so
     * they always commit. The run begins by claiming the source (RUNNING only if not already), and a
     * source another run holds is skipped. Precondition: [source] is a persisted entity, and callers
     * must not manipulate its status. Acquires a permit from [importSemaphore] for the full duration;
     * the persisted-id precondition is checked before taking one.
     *
     * @param force fetch the listing without the cached `If-None-Match` / `If-Modified-Since`
     * headers (#1159): a parser fix at a venue whose page has not changed otherwise 304s on every
     * run. Per call, and the run stores the response's validators like any other.
     */
    internal suspend fun importFromSource(
        source: EventSourceEntity,
        force: Boolean = false
    ): ImportResultResponse {
        requireNotNull(source.id) { "Event source must be persisted (have a non-null id) before importing" }
        // The one place the log context for a run is established (#380): MDCContext travels with the
        // coroutine, so every line below carries the slug and run id. Outside the semaphore, so a run
        // waiting for a permit says which source it is waiting for.
        return withContext(LogContext.forImportRun(source.slug)) {
            importSemaphore.withPermit { timedImportPipeline(source, force) }
        }
    }

    /**
     * Wraps [runImportPipeline] with the run timer and the outcome counter (#415). The outcome is set
     * at each exit rather than derived from the [ImportResultResponse], which cannot distinguish a
     * not-modified run, a skipped claim and a misconfigured source: all `imported=false,
     * eventCount=0`. `finally`, so a run records exactly once, including one that throws.
     */
    private suspend fun timedImportPipeline(
        source: EventSourceEntity,
        force: Boolean
    ): ImportResultResponse {
        val startedAt = System.nanoTime()
        // FAILED rather than a nullable: if an exception escapes every branch, "the run failed" is the
        // honest reading.
        var outcome = ImporterMetrics.RunOutcome.FAILED
        try {
            val (response, runOutcome) = runImportPipeline(source, force)
            outcome = runOutcome
            return response
        } finally {
            metrics.recordRun(source.slug, outcome, (System.nanoTime() - startedAt).nanoseconds)
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Intentional: record any failure; multiple early returns for error paths
    private suspend fun runImportPipeline(
        source: EventSourceEntity,
        force: Boolean
    ): Pair<ImportResultResponse, ImporterMetrics.RunOutcome> {
        val eventSourceEnum =
            try {
                EventSource.valueOf(source.sourceType)
            } catch (_: IllegalArgumentException) {
                val error = "Unknown source type '${source.sourceType}'"
                logger.error { error }
                // Will never self-resolve on retry, so MISCONFIGURED instead of FAILED to avoid consuming retry
                // budget.
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

        if (force) logger.info { "Fetching source page unconditionally (forced) for '${runningSource.slug}'" }
        val (etag, lastModified) = runningSource.validatorsFor(force)
        return try {
            when (val result = importer.importEvents(runningSource.url, etag, lastModified)) {
                is ImportResult.NotModified -> {
                    logger.info { "Source '${runningSource.slug}' not modified, skipping import" }
                    // The count carries forward rather than resetting to 0 (#659): a 304 says the listing has not
                    // changed. Writing 0 made an unchanged source indistinguishable from an emptied one; `loge`
                    // reported `lastEventCount = 0` on a run that succeeded with six events.
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

                    // One transaction for upserts and cleanup, via TransactionalOperator so the status updates stay
                    // outside it and always commit. PROHIBITED means the field is never stored (#807).
                    val licences = runningSource.licences()
                    val upsert =
                        transactionalOperator.executeAndAwait {
                            val sourceId = requireNotNull(runningSource.id) { "Event source must be persisted before importing" }
                            eventUpsertService.upsertAndCleanup(result.events, runningSource.venueId, venue.slug, sourceId, licences)
                        }

                    afterCommit(runningSource, venue.name, result, upsert, licences)

                    markSuccess(runningSource, upsert.total, result.etag, result.lastModified)
                    afterSuccess(runningSource, upsert)
                    ImportResultResponse(sourceSlug = runningSource.slug, imported = true, eventCount = upsert.total) to
                        ImporterMetrics.RunOutcome.SUCCESS
                }
            }
        } catch (e: Exception) {
            val error = e.message ?: "Unknown error during import"
            // The streak, because one broken venue writes this line once per attempt. Not "of maxRetries": a
            // source past its budget keeps running on its plain interval (#659). `retryCount` is the value
            // before `markFailed` adds this failure.
            logger.error(e) { "Import failed for source '${runningSource.slug}' (consecutive failures: ${runningSource.retryCount + 1}): $error" }
            metrics.recordScrapeFailure(runningSource.slug, scrapeFailureReason(e))
            markFailed(runningSource, error)
            ImportResultResponse(sourceSlug = runningSource.slug, imported = false, eventCount = 0, error = error) to
                ImporterMetrics.RunOutcome.FAILED
        }
    }

    // -- Event source status management --

    /**
     * Claims [source] for this run by moving it to RUNNING, or returns `null` when another run holds
     * the claim. A conditional UPDATE decided by the database ([EventSourceRepository.claimForImport]):
     * a read-then-save cannot serialize two callers queued on [importSemaphore]. Gated on the version
     * [source] was read at, so it fails whenever the row changed since the read, which a status check
     * misses: a scheduler tick can read a source while IDLE and reach its claim after a manual trigger
     * has already imported it and left it SUCCESS again.
     *
     * The returned entity mirrors the claim in memory: the UPDATE touches exactly the four columns
     * reproduced here, and `version + 1` is the value persisted, so the closing save matches on the
     * first attempt. A source left RUNNING by a crashed run is released by
     * [ScheduledImportService.resetStuckSources] (default 30 minutes).
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
     * Everything a successful run does once its transaction has committed, in order. The meters wait
     * for the commit, since an increment cannot be taken back. Field coverage is measured from what
     * the scraper extracted, not the stored rows, where a selector that stopped matching is invisible
     * until old rows age out (#472); it runs before `markSuccess` and is unguarded because `record`
     * never throws. The translation pass is guarded: derived text, so an engine that is down must
     * never fail a scrape that worked (ADR-026). The MusicBrainz pass runs in [afterSuccess].
     */
    private suspend fun afterCommit(
        source: EventSourceEntity,
        venueName: String,
        result: ImportResult.Success,
        upsert: UpsertOutcome,
        licences: SourceLicences
    ) {
        metrics.recordUpsertOutcome(source.slug, upsert, result.droppedUnresolvedDate)
        fieldCoverageService.record(source, result.events)
        runCatching { descriptionTranslationService.translateFor(source, venueName, licences) }
            .onFailure { logger.warn(it) { "TranslationRequest pass failed for '${source.slug}'" } }
    }

    /**
     * The MusicBrainz pass, after `markSuccess` (#1604): the slowest thing a run does, one request a
     * second over the billed artists and a slice of the backfill (ADR-031), nine minutes for a full
     * slice and five more per 503, and a source `RUNNING` that long is one bad slice from
     * `app.scheduling.staleness-timeout` reaping it. Guarded like the translation pass.
     */
    private suspend fun afterSuccess(
        source: EventSourceEntity,
        upsert: UpsertOutcome
    ) {
        runCatching { musicBrainzLookupService.lookupFor(source, upsert.touchedArtistIds) }
            .onFailure { logger.warn(it) { "MusicBrainz pass failed for '${source.slug}'" } }
    }

    /**
     * Closes a run that worked. `lastSuccessAt` is written only here; `lastImportAt` also by
     * [markFailed] and the claim, so it is a last-attempt time, and the pair keeps
     * `importer.source.last_success` correct across a failure (#415). A 304 is a working scraper.
     *
     * @param eventCount how many events the run read, or `null` for a 304, which carries the
     * previous count forward. `null` reaches the column only on a first attempt that is a 304,
     * impossible without an `etag` from an earlier one.
     */
    private suspend fun markSuccess(
        source: EventSourceEntity,
        eventCount: Int?,
        newEtag: String? = source.etag,
        newLastModified: String? = source.lastModified
    ): EventSourceEntity {
        val robots = robotsRulesCache.check(source.url)
        return saveWithVersionConflictRetry(source) {
            val now = Instant.now(clock)
            it
                .copy(
                    status = ImportStatus.SUCCESS.name,
                    lastImportAt = now,
                    lastSuccessAt = now,
                    lastEventCount = eventCount,
                    lastError = null,
                    etag = newEtag,
                    lastModified = newLastModified,
                    retryCount = 0
                ).withRobots(robots)
        }
    }

    private suspend fun markFailed(
        source: EventSourceEntity,
        error: String
    ): EventSourceEntity {
        // Recorded on failure too: a source blocked by robots.txt fails every run, and the columns say
        // which of the two it is.
        val robots = robotsRulesCache.check(source.url)
        return saveWithVersionConflictRetry(source) {
            it
                .copy(
                    status = ImportStatus.FAILED.name,
                    lastImportAt = Instant.now(clock),
                    lastError = error.take(MAX_ERROR_LENGTH),
                    retryCount = it.retryCount + 1
                ).withRobots(robots)
        }
    }

    /**
     * Marks a source as misconfigured, a permanent error that never self-resolves. Does NOT
     * increment [EventSourceEntity.retryCount]; the scheduler skips MISCONFIGURED sources until a
     * manual retry resets them to IDLE.
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
     * Saves [source] after applying [mutation], with one retry on [OptimisticLockingFailureException]:
     * [ScheduledImportService.resetStuckSources] can modify the row between the claim and
     * `markSuccess`/`markFailed` (ADR-009). On conflict the entity is re-fetched, [mutation]
     * re-applied, the save retried once; a second failure propagates to the next tick.
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

/** What this source permits, read from its three licence columns (#283, ADR-026). */
private fun EventSourceEntity.licences(): SourceLicences = SourceLicences.of(descriptionLicence, imageLicence, translationLicence)

/**
 * Applies what the host's `robots.txt` said about a source's entry URL (#790). A file-level
 * extension because as a member it pushed [EventImportService] past `TooManyFunctions`.
 */
private fun EventSourceEntity.withRobots(check: RobotsCheck): EventSourceEntity =
    copy(
        robotsCheckedAt = check.checkedAt,
        robotsAllowed = check.allowed,
        robotsTxtUrl = check.robotsTxtUrl
    )

/**
 * The cached validators this run sends, `etag to lastModified`, neither when [force] (#1159).
 * Null validators make `HtmlFetcher.fetch` unconditional, so one call site covers every
 * conditional importer.
 */
private fun EventSourceEntity.validatorsFor(force: Boolean): Pair<String?, String?> = if (force) null to null else etag to lastModified
