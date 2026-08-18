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
 * **The cost, stated so nobody reads one of these as live:** all four are as stale as
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
            republishLastSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Could not refresh the metric gauges; they keep their previous values" }
        }
    }

    /**
     * Publishes `importer.source.last_success` for every source whose last run actually succeeded.
     *
     * Done on every tick rather than once at startup, which costs one query and buys two things: the
     * gauge exists within a minute of a restart instead of only after that source's next run — up to
     * 24 hours on a daily interval, during which the most important alert in the system would have
     * nothing to evaluate — and a source that succeeds while this instance is running is re-asserted
     * from the database rather than only from memory.
     *
     * **It is deliberately incomplete, and this is the known limit of the metric.**
     * `event_source.last_import_at` is written on failure as well as success, so it means *last
     * attempt*. A source whose most recent attempt failed therefore publishes nothing here, even if
     * it succeeded an hour before — its true last-success time is not recorded anywhere in the
     * schema. Seeding from `last_import_at` regardless would be worse than absent: it would assert a
     * success that did not happen, and this gauge exists precisely to notice that. **An absent series
     * is alertable; a wrong one is not.**
     *
     * Fixing it properly needs a `last_success_at` column, which is a schema change and does not
     * belong in an instrumentation change.
     */
    private suspend fun republishLastSuccess() {
        eventSourceRepository
            .findByEnabledTrue()
            .toList()
            .filter { it.status == ImportStatus.SUCCESS.name }
            .forEach { source -> source.lastImportAt?.let { metrics.publishLastSuccess(source.slug, it.epochSecond) } }
    }
}
