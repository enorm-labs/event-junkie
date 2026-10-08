package de.norm.events.importing

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

/**
 * Launches event imports as **fire-and-forget** background jobs.
 *
 * Manual import triggers ([EventSourceController]'s `POST …/import` endpoints) must not
 * block the HTTP request for the full duration of an import: a heavy two-page importer
 * (e.g. Badehaus) makes dozens of throttled detail-page fetches and can run for a minute
 * or more, long enough for the calling HTTP client to hit its read timeout and cancel the
 * request — which would cancel the request-scoped coroutine and abort the import
 * mid-transaction, leaving the source stuck in `RUNNING` and nothing persisted.
 *
 * This launcher runs each import on an application-scoped [CoroutineScope] backed by a
 * [SupervisorJob], so the import's lifetime is decoupled from any request. One import
 * failing never cancels the scope or sibling imports. Progress and outcome are recorded on
 * the [EventSourceEntity] by [EventImportService] (RUNNING → SUCCESS/FAILED), so callers
 * poll `GET /api/admin/event-sources/{slug}` to observe them.
 *
 * The scope is cancelled on application shutdown via [DisposableBean.destroy], which waits for
 * each cancelled run to record its FAILED status.
 */
@Component
class ImportJobLauncher(
    private val eventImportService: EventImportService,
    private val eventSourceRepository: EventSourceRepository,
    @Qualifier("ioDispatcher") ioDispatcher: CoroutineDispatcher
) : DisposableBean {
    private val logger = KotlinLogging.logger {}

    /** Application-scoped supervisor scope: a failing import never cancels the scope or siblings. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Launches a background import of all enabled sources. Returns immediately. */
    fun triggerImportAll() = launch("all enabled sources") { eventImportService.importAll() }

    /**
     * Validates that a source with [slug] exists (throwing [EventSourceNotFoundException] if
     * not, so the caller can return `404` synchronously), then launches its import in the
     * background and returns immediately.
     *
     * @param force skip the conditional headers on this one fetch — see
     *   [EventImportService.importFromSource].
     */
    suspend fun triggerImportBySlug(
        slug: String,
        force: Boolean = false
    ) {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        val description = if (force) "source '$slug' (forced)" else "source '$slug'"
        launch(description) { eventImportService.importFromSource(source, force) }
    }

    @Suppress("TooGenericExceptionCaught") // Fire-and-forget: never let a background failure escape unlogged
    private fun launch(
        description: String,
        block: suspend () -> Unit
    ) {
        scope.launch {
            try {
                logger.info { "Background import for $description started" }
                block()
                logger.info { "Background import for $description finished" }
            } catch (e: Exception) {
                logger.error(e) { "Background import for $description failed" }
            }
        }
    }

    /**
     * Cancels the running imports and waits, bounded, while each one writes its FAILED status (#2286).
     * The R2DBC pool is destroyed after this bean, so a write that ran later would find it closed.
     * `runBlocking` is fine here: shutdown runs on the main thread, not an event loop.
     */
    override fun destroy() {
        val closed = runBlocking { withTimeoutOrNull(SHUTDOWN_GRACE) { scope.coroutineContext.job.cancelAndJoin() } }
        if (closed == null) logger.warn { "Background imports still closing after $SHUTDOWN_GRACE at shutdown; their sources stay RUNNING" }
    }

    private companion object {
        /** Long enough for one status write per run; Kubernetes allows 30 s for the whole shutdown. */
        val SHUTDOWN_GRACE = 10.seconds
    }
}
