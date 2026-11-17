package de.norm.events.dataquality.classification

import de.norm.events.common.runScheduled
import de.norm.events.dataquality.ClassificationRunInProgressException
import de.norm.events.dataquality.DataQualityMetrics
import de.norm.events.dataquality.flyer.FlyerLineupCheck
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
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/** Why a nightly pass ended before its last source. */
enum class PassStop { NIGHT_CAP, TOKEN_BUDGET }

/** What one nightly pass did: the sources it ran, each once in the order of its first turn, its model calls, and why it stopped early, if it did. */
data class NightlyPassResult(
    val sources: List<String>,
    val calls: Int,
    val stoppedBy: PassStop?,
    /** Answers that were no JSON at all (#4108). */
    val notJson: Int = 0
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
@Suppress("LongParameterList") // The pass orders sources from two reads, spends two limits and stamps its end.
class ClassificationRunner(
    private val check: EventClassificationCheck,
    private val repository: EventClassificationRepository,
    private val sourceRepository: EventSourceRepository,
    private val metrics: DataQualityMetrics,
    private val ledger: ClassificationTokenLedger,
    private val properties: ClassificationProperties,
    private val passMetrics: ClassificationMetrics,
    @Qualifier("ioDispatcher") ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC(),
    /** The flyer pass (ADR-059), present only while `app.data-quality.flyer.enabled` is set. */
    private val flyerCheck: FlyerLineupCheck? = null
) : DisposableBean {
    private val logger = KotlinLogging.logger {}
    private val lock = Mutex()

    init {
        passMetrics.seed(clock.instant())
    }

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

    @Scheduled(cron = $$"${app.data-quality.classification.cron:$${ClassificationProperties.DEFAULT_CRON}}")
    suspend fun nightly() = runScheduled("Nightly event classification", logger) { runNightly() }

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

    /** Every pass that ends is stamped, a spent month's skip too: `ej-classification-budget` reports that one. */
    private suspend fun pass(): NightlyPassResult? = runPass().also { passMetrics.markSucceeded(clock.instant()) }

    private suspend fun runPass(): NightlyPassResult? {
        val used = ledger.monthTotal()
        if (used >= ledger.budget) {
            logger.warn { "Nightly event classification skipped: $used of ${ledger.budget} tokens used this month" }
            return null
        }
        val regressed = metrics.regressedSources()
        val pendingBySource = repository.countPendingCandidates()
        val order = order(pendingBySource, regressed)
        val share = share(properties.maxPerRun)
        logger.info {
            "Nightly event classification started: ${order.size} source(s) with candidates, ${pendingBySource.count { it.value > 0 }} " +
                "with candidates that need a model call, ${order.count { it in regressed }} with a data-quality regression first, " +
                "at most ${properties.maxPerRun} model call(s), $share a source per turn"
        }
        val ran = linkedSetOf<String>()
        var calls = 0
        var notJson = 0
        var stop: PassStop? = null
        val pending = ArrayDeque(order)
        while (stop == null && pending.isNotEmpty()) {
            val left = properties.maxPerRun - calls
            if (left <= 0) {
                stop = PassStop.NIGHT_CAP
            } else {
                val slug = pending.removeFirst()
                val result = check.checkSource(slug, maxCalls = minOf(share, left))
                ran += slug
                calls += result.checked
                notJson += result.notJson
                if (result.overBudget > 0) stop = PassStop.TOKEN_BUDGET
                // Back to the end of the queue while candidates wait over its share, so one source cannot take the night (#5236).
                if (result.overCap > 0) pending.addLast(slug)
            }
        }
        val result = NightlyPassResult(ran.toList(), calls, stop, notJson).also { report(it, order.size) }
        // The flyers share the month's budget, so a pass that spent it reads none (ADR-041 rule 5).
        if (stop != PassStop.TOKEN_BUDGET) flyerCheck?.pass()
        return result
    }

    private fun report(
        result: NightlyPassResult,
        total: Int
    ) {
        val summary = "${result.sources.size} of $total source(s), ${result.calls} model call(s), ${result.notJson} answer(s) not JSON"
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
        /** A turn gives a source at most a tenth of `max-per-run`, so a night that hits the cap reaches ten sources or more. */
        private const val TURNS_PER_NIGHT = 10

        /** The calls one source may make before the pass moves on to the next one. At least one. */
        fun share(maxPerRun: Int): Int = maxOf(1, (maxPerRun + TURNS_PER_NIGHT - 1) / TURNS_PER_NIGHT)

        /**
         * Sources with a regression first, then the most candidates that would cost a model call first, so a source whose
         * candidates are all unchanged comes last (#5236). The slug breaks a tie, so the order is stable.
         */
        fun order(
            pending: Map<String, Long>,
            regressed: Set<String>
        ): List<String> =
            pending.entries
                .sortedWith(compareBy<Map.Entry<String, Long>> { it.key !in regressed }.thenByDescending { it.value }.thenBy { it.key })
                .map { it.key }
    }
}
