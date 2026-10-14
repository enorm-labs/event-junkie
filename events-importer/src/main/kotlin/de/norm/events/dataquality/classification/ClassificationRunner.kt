package de.norm.events.dataquality.classification

import de.norm.events.dataquality.ClassificationRunInProgressException
import de.norm.events.dataquality.DataQualityMetrics
import de.norm.events.importing.EventSourceNotFoundException
import de.norm.events.importing.EventSourceRepository
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
import kotlin.coroutines.cancellation.CancellationException

/** Why a nightly pass ended before its last source. */
enum class PassStop { NIGHT_CAP, TOKEN_BUDGET }

/** What one nightly pass did: the sources it ran, in order, its model calls, and why it stopped early, if it did. */
data class NightlyPassResult(
    val sources: List<String>,
    val calls: Int,
    val stoppedBy: PassStop?
)

/**
 * Runs [EventClassificationCheck] outside a request: the nightly pass, and a source run the admin route
 * starts (#2996). One run at a time, so the model sees one call at a time and the night cap holds.
 *
 * The `@Scheduled` method is untagged, so `app.scheduling.enabled=false` stops it through
 * `SchedulingConfiguration` (#949). The bean exists only while the check is on.
 */
@Component
@ConditionalOnProperty(name = ["app.data-quality.classification.enabled"], havingValue = "true")
@Suppress("LongParameterList") // The pass orders sources from two reads and spends two limits.
class ClassificationRunner(
    private val check: EventClassificationCheck,
    private val repository: EventClassificationRepository,
    private val sourceRepository: EventSourceRepository,
    private val metrics: DataQualityMetrics,
    private val ledger: ClassificationTokenLedger,
    private val properties: ClassificationProperties,
    @Qualifier("ioDispatcher") ioDispatcher: CoroutineDispatcher
) : DisposableBean {
    private val logger = KotlinLogging.logger {}
    private val lock = Mutex()

    /** Outlives the request, so a run is not cancelled when the caller's port-forward times out. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Starts a run of one source in the background and returns its job. An unknown slug and a run
     * that is going fail here, before anything starts, so the route can answer 404 or 409.
     */
    suspend fun startSource(
        slug: String,
        force: Boolean
    ): Job {
        sourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        if (!lock.tryLock()) throw ClassificationRunInProgressException()
        return scope.launch {
            try {
                runSource(slug, force)
            } finally {
                lock.unlock()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Background: a failure must not escape unlogged
    private suspend fun runSource(
        slug: String,
        force: Boolean
    ) {
        try {
            check.checkSource(slug, force, properties.maxPerRun)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Background event classification of source '$slug' failed" }
        }
    }

    /** The nightly pass. Nothing escapes: an uncaught exception cancels a `@Scheduled` task for the life of the process. */
    @Scheduled(cron = $$"${app.data-quality.classification.cron:$${ClassificationProperties.DEFAULT_CRON}}")
    @Suppress("TooGenericExceptionCaught") // Intentional: see the KDoc
    suspend fun nightly() {
        try {
            runNightly()
        } catch (e: Exception) {
            logger.error(e) { "Nightly event classification failed" }
        }
    }

    /** One pass, or `null` when another run held the lock or the month's budget is spent. */
    suspend fun runNightly(): NightlyPassResult? {
        if (!lock.tryLock()) {
            logger.warn { "Nightly event classification skipped: another run is going" }
            return null
        }
        return try {
            pass()
        } finally {
            lock.unlock()
        }
    }

    private suspend fun pass(): NightlyPassResult? {
        val used = ledger.monthTotal()
        if (used >= ledger.budget) {
            logger.warn { "Nightly event classification skipped: $used of ${ledger.budget} tokens used this month" }
            return null
        }
        val regressed = metrics.regressedSources()
        val order = order(repository.countSourceCandidates(), regressed)
        logger.info {
            "Nightly event classification started: ${order.size} source(s) with candidates, ${order.count { it in regressed }} " +
                "with a data-quality regression first, at most ${properties.maxPerRun} model call(s)"
        }
        val ran = mutableListOf<String>()
        var calls = 0
        var stop: PassStop? = null
        val pending = ArrayDeque(order)
        while (stop == null && pending.isNotEmpty()) {
            val left = properties.maxPerRun - calls
            if (left <= 0) {
                stop = PassStop.NIGHT_CAP
            } else {
                val slug = pending.removeFirst()
                val result = check.checkSource(slug, maxCalls = left)
                ran += slug
                calls += result.checked
                if (result.overBudget > 0) stop = PassStop.TOKEN_BUDGET
            }
        }
        return NightlyPassResult(ran, calls, stop).also { report(it, order.size) }
    }

    private fun report(
        result: NightlyPassResult,
        total: Int
    ) {
        val summary = "${result.sources.size} of $total source(s), ${result.calls} model call(s)"
        when (result.stoppedBy) {
            null -> {
                logger.info { "Nightly event classification finished: $summary" }
            }

            PassStop.NIGHT_CAP -> {
                logger.info { "Nightly event classification reached max-per-run: $summary; the rest wait for the next night" }
            }

            PassStop.TOKEN_BUDGET -> {
                logger.warn { "Nightly event classification stopped at the monthly token budget of ${ledger.budget}: $summary" }
            }
        }
    }

    override fun destroy() = scope.cancel()

    companion object {
        /** Sources with a regression first, then the most candidates first. The slug breaks a tie, so the order is stable. */
        fun order(
            candidates: Map<String, Long>,
            regressed: Set<String>
        ): List<String> =
            candidates.entries
                .sortedWith(compareBy<Map.Entry<String, Long>> { it.key !in regressed }.thenByDescending { it.value }.thenBy { it.key })
                .map { it.key }
    }
}
