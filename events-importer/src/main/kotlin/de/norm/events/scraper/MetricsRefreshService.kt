package de.norm.events.scraper

import de.norm.events.event.EventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * Keeps the gauges current, because a gauge in a reactive application cannot fetch its own value.
 *
 * Micrometer reads a gauge **synchronously, at scrape time**, on the thread serving
 * `/actuator/prometheus`. Every query this application can make is suspending, so a supplier that
 * asked the database would have to block a Netty event-loop thread — the one thing ADR-001 rules out
 * everywhere else in this codebase. The values are therefore refreshed on a schedule into the
 * atomics [ImporterMetrics] holds, and each gauge only reads a number.
 *
 * **The cost, stated so nobody reads one of these as live:** all five are as stale as
 * `app.metrics.refresh-interval-ms` (default 60s). For counts that move on an import cycle measured
 * in hours that is irrelevant; it would matter for anything driving a synchronous decision, and
 * nothing here does.
 *
 * The counters are not refreshed here. A counter is recorded where the thing happens, which is why
 * only gauges appear below.
 */
@Service
class MetricsRefreshService(
    private val eventRepository: EventRepository,
    private val eventSourceRepository: EventSourceRepository,
    private val metrics: ImporterMetrics,
    /** Injected clock for deterministic time in tests, as elsewhere in this module. */
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Refreshes every polled gauge.
     *
     * `suspend` rather than a `runBlocking` bridge: Spring Boot 4 dispatches a suspending
     * `@Scheduled` method on its own scheduler, the same property [ScheduledImportService.tick]
     * relies on, so nothing here occupies a Netty event-loop thread.
     *
     * `fixedDelayString` rather than `fixedRate`: on a rate, a refresh that outlives its interval
     * queues the next one immediately, and a slow database turns into a queue of queries against the
     * database that is already slow.
     *
     * **Failures are caught, not propagated.** A monitoring refresh able to kill the scheduler would
     * make observability a source of outages — and the scheduler it shares is the one that runs the
     * imports. The visible result of this failing is a gauge that stops moving, which is itself
     * detectable (`changes()` over a window) and strictly better than a dead importer.
     */
    @Scheduled(fixedDelayString = $$"${app.metrics.refresh-interval-ms:60000}")
    @Suppress("TooGenericExceptionCaught") // Intentional: monitoring must not be able to fail the application
    suspend fun refreshGauges() {
        try {
            metrics.updateEventCounts(
                total = eventRepository.count(),
                future = eventRepository.countByEventDateGreaterThanEqual(LocalDate.now(clock))
            )
            metrics.updateSourcesRunning(eventSourceRepository.countByStatus(ImportStatus.RUNNING.name))
            republishSourceState()
        } catch (e: Exception) {
            logger.warn(e) { "Could not refresh the metric gauges; they keep their previous values" }
        }
    }

    /**
     * Publishes the two per-source gauges: `last_success` for every source that has ever succeeded,
     * and `has_succeeded` for **every** source, so that one which never has is still visible.
     *
     * Done on every tick rather than once at startup, which costs one query and buys two things: the
     * gauge exists within a minute of a restart instead of only after that source's next run — up to
     * 24 hours on a daily interval, during which the most important alert in the system would have
     * nothing to evaluate — and a source that succeeds while this instance is running is re-asserted
     * from the database rather than only from memory.
     *
     * **This used to be incomplete, and the completion is the point of the `last_success_at` column.**
     * It previously filtered on `status == SUCCESS` and read `last_import_at`, because that was the
     * only timestamp in the schema — and `last_import_at` is written on failure too, so it means
     * *last attempt*. A source whose most recent run failed therefore published **nothing**, exactly
     * when the staleness alert most needed a value: the series vanished at the moment the source
     * broke, so `time() - importer_source_last_success_seconds > 3 * interval` had no series to
     * evaluate and an absence-blind alert stayed silent. Now every source that has ever succeeded
     * keeps publishing its real last-success time while it is failing, which is what makes the
     * staleness rule fire rather than go quiet.
     *
     * Sources that have never succeeded still publish **no last-success**, and that stays deliberate:
     * there is no true value to assert, and a zero would read as "1970" to every rule written on this
     * gauge. They publish `importer.source.has_succeeded = 0` instead, which is the point below.
     *
     * ## And `has_succeeded`, which exists for every source whether or not it ever worked (#618)
     *
     * The paragraph above closed half the blind spot: a source that *had* worked keeps its series
     * while it is failing. The other half is a source that has **never** worked, which had no series
     * at all — so it could not be stale, late or failing, only absent. Staging on 2026-08-20: 86
     * sources, 84 series, and the two missing were the only two that were broken.
     *
     * **The population emptied and the mechanism did not.** Both venues fixed themselves on the
     * 08-21 retry, so the count went to zero — which is the more dangerous state, because "0 sources
     * stale" and "nothing is broken" then agree, and go on agreeing right through the next source
     * that is added and never works. `/next-importer` adds one at a time, so that window is routine
     * rather than hypothetical.
     *
     * **Every enabled row gets a `has_succeeded` series from the first tick after start-up**,
     * regardless of import history — which is exactly what a per-run counter cannot do, since it
     * lives in the process and vanishes on the deploy that restarts the pod.
     */
    private suspend fun republishSourceState() {
        eventSourceRepository
            .findByEnabledTrue()
            .toList()
            .forEach { source ->
                val succeeded = source.lastSuccessAt
                if (succeeded != null) {
                    // Also sets has_succeeded = 1, so the two gauges cannot disagree about this source.
                    metrics.publishLastSuccess(source.slug, succeeded.epochSecond)
                } else {
                    metrics.publishHasSucceeded(source.slug, succeeded = false)
                }
            }
    }
}
