package de.norm.events.instagram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException

/** Why a pass ended before its last venue. */
enum class InstagramPassStop { CALL_CAP, ACCESS_REFUSED }

/** What one pass did: the venues it read, the drafts it stored, its model calls, and why it stopped early, if it did. */
data class InstagramPassResult(
    val venues: Int,
    val drafts: Int,
    val calls: Int,
    val removed: Long,
    val stoppedBy: InstagramPassStop?
)

/**
 * Runs [InstagramDraftService] outside a request: the daily pass, and a venue or a pass the admin route
 * starts. One run at a time, so the cap per run holds.
 *
 * A pass first deletes the drafts nobody judged in [InstagramProperties.pendingDays] (ADR-048 rule 6).
 * It stops at the first refused access, because a missing permission or an expired token fails every
 * venue alike. The `@Scheduled` method is untagged, so `app.scheduling.enabled=false` stops it.
 */
@Component
@ConditionalOnProperty(name = ["app.instagram.enabled"], havingValue = "true")
@Suppress("LongParameterList") // Two stores, the service, metrics and limits, plus the dispatcher and a clock for the test.
class InstagramDraftRunner(
    private val service: InstagramDraftService,
    private val repository: InstagramDraftRepository,
    private val venues: InstagramVenueRepository,
    private val metrics: InstagramMetrics,
    private val properties: InstagramProperties,
    @Qualifier("ioDispatcher") ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC()
) : DisposableBean {
    private val logger = KotlinLogging.logger {}
    private val lock = Mutex()

    /** Outlives the request, so a run is not cancelled when the caller's port-forward times out. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Starts a read of one venue in the background. An unknown or switched-off venue and a running pass fail here. */
    suspend fun startVenue(slug: String): Job {
        val venue = venues.findVenues().firstOrNull { it.slug == slug } ?: throw InstagramVenueNotFoundException(slug)
        return launchLocked("Instagram read of venue '$slug'") { read(venue, properties.maxPerRun) }
    }

    /** Starts a whole pass in the background, as the schedule would. */
    fun startPass(): Job = launchLocked("Instagram pass") { pass() }

    /** The daily pass. Nothing escapes: an uncaught exception cancels a `@Scheduled` task for the life of the process. */
    @Scheduled(cron = $$"${app.instagram.cron:$${InstagramProperties.DEFAULT_CRON}}")
    @Suppress("TooGenericExceptionCaught") // Intentional: see the KDoc
    suspend fun daily() {
        try {
            runDaily()
        } catch (e: Exception) {
            logger.error(e) { "The Instagram pass failed" }
        }
    }

    /** One pass, or `null` when another run held the lock. */
    suspend fun runDaily(): InstagramPassResult? {
        if (!lock.tryLock()) {
            logger.warn { "The Instagram pass was skipped: another run is going" }
            return null
        }
        return try {
            pass()
        } finally {
            lock.unlock()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Background: a failure must not escape unlogged
    private fun launchLocked(
        what: String,
        block: suspend () -> Unit
    ): Job {
        if (!lock.tryLock()) throw InstagramRunInProgressException()
        return scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "$what failed" }
            } finally {
                lock.unlock()
            }
        }
    }

    private suspend fun pass(): InstagramPassResult {
        val removed = repository.deletePendingCreatedBefore(OffsetDateTime.now(clock).minusDays(properties.pendingDays))
        val switchedOn = venues.findVenues()
        logger.info {
            "The Instagram pass started: ${switchedOn.size} venue(s), at most ${properties.maxPerRun} model call(s), " +
                "$removed unjudged draft(s) older than ${properties.pendingDays} days deleted"
        }
        var drafts = 0
        var calls = 0
        var read = 0
        var stop: InstagramPassStop? = null
        val pending = ArrayDeque(switchedOn)
        while (stop == null && pending.isNotEmpty()) {
            val left = properties.maxPerRun - calls
            if (left <= 0) {
                stop = InstagramPassStop.CALL_CAP
            } else {
                val result = read(pending.removeFirst(), left)
                read++
                if (result is VenueDraftResult.Read) {
                    drafts += result.drafts
                    calls += result.calls
                }
                if (result is VenueDraftResult.NotRead && result.result is DiscoveryResult.AccessRefused) stop = InstagramPassStop.ACCESS_REFUSED
            }
        }
        return InstagramPassResult(read, drafts, calls, removed, stop).also { report(it, switchedOn.size) }
    }

    private suspend fun read(
        venue: InstagramVenue,
        maxCalls: Int
    ): VenueDraftResult = service.draftFrom(venue, maxCalls).also { record(venue, it) }

    private fun record(
        venue: InstagramVenue,
        result: VenueDraftResult
    ) {
        when (result) {
            is VenueDraftResult.Read -> {
                metrics.postsRead(result.posts)
                metrics.drafts(result.drafts)
                metrics.extraction("failed", result.failed)
                metrics.extraction("rejected", result.rejected)
                logger.info {
                    "Instagram venue '${venue.slug}': ${result.posts} post(s), ${result.newPosts} new, ${result.drafts} draft(s), " +
                        "${result.notEvents} without an event, ${result.rejected} answer(s) refused, ${result.failed} call(s) failed, " +
                        "${result.calls} model call(s), ${result.tokens} token(s)"
                }
            }

            VenueDraftResult.NoHandle -> {
                logger.warn { "Instagram venue '${venue.slug}' is switched on, but its instagram_url names no profile" }
            }

            is VenueDraftResult.NotRead -> {
                notRead(venue, result.result)
            }
        }
    }

    private fun notRead(
        venue: InstagramVenue,
        result: DiscoveryResult
    ) {
        when (result) {
            is DiscoveryResult.AccountUnreadable -> {
                metrics.graphError("unreadable")
                logger.warn { "Instagram venue '${venue.slug}' was not read: Graph error ${result.code}, a wrong handle or a personal account" }
            }

            is DiscoveryResult.AccessRefused -> {
                metrics.graphError("refused")
                logger.error {
                    "Instagram access refused at venue '${venue.slug}' (Graph error ${result.code}: ${result.message}). " +
                        "No venue can be read until the token or the app's permission is fixed"
                }
            }

            is DiscoveryResult.Unavailable -> {
                metrics.graphError("unavailable")
                logger.warn { "Instagram venue '${venue.slug}' was not read: ${result.reason}. The next pass tries again" }
            }

            // A venue that was read is reported by [record].
            is DiscoveryResult.Posts -> {}
        }
    }

    private fun report(
        result: InstagramPassResult,
        total: Int
    ) {
        val summary = "${result.venues} of $total venue(s), ${result.drafts} draft(s), ${result.calls} model call(s)"
        when (result.stoppedBy) {
            null -> logger.info { "The Instagram pass finished: $summary" }
            InstagramPassStop.CALL_CAP -> logger.info { "The Instagram pass reached max-per-run: $summary; the rest wait for the next pass" }
            InstagramPassStop.ACCESS_REFUSED -> logger.error { "The Instagram pass stopped at a refused access: $summary" }
        }
    }

    override fun destroy() = scope.cancel()
}

/** Thrown for a venue that does not exist, or that a read needs switched on and is not. Rendered as a 404. */
class InstagramVenueNotFoundException(
    slug: String
) : RuntimeException("No venue '$slug' to read for Instagram drafts")

/** Thrown when an Instagram run is asked for while one is going. Rendered as a 409. */
class InstagramRunInProgressException : RuntimeException("An Instagram run is going. Start another one when it has finished")

/** Thrown when an Instagram route is called while the drafts are switched off. Rendered as a 409. */
class InstagramDraftsDisabledException : RuntimeException("Instagram drafts are off: set app.instagram.enabled, a token and a key")
