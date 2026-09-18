package de.norm.events.scraper

import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Keeps the gauges current, because a gauge in a reactive application cannot fetch its own value.
 *
 * Micrometer reads a gauge **synchronously, at scrape time**, on the thread serving
 * `/actuator/prometheus`. Every query this application can make is suspending, so a supplier that
 * asked the database would have to block a Netty event-loop thread — the one thing ADR-001 rules out
 * everywhere else in this codebase. The values are therefore refreshed on a schedule into the
 * atomics [ImporterMetrics] holds, and each gauge only reads a number.
 *
 * **The cost, stated so nobody reads one of these as live:** all six are as stale as
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
    private val artistRepository: ArtistRepository,
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
            metrics.updateMusicBrainzUnchecked(artistRepository.countUncheckedByMusicBrainz())
            republishSourceState()
        } catch (e: Exception) {
            logger.warn(e) { "Could not refresh the metric gauges; they keep their previous values" }
        }
    }

    /**
     * Publishes the per-source gauges: `last_success` for every source that has ever succeeded, and
     * `has_succeeded`, `events_future` and `days_since_future_event` for **every** source.
     *
     * Republished every tick rather than once at start-up. That costs one query and buys two things:
     * the gauge exists within a minute of a restart rather than only after that source's next run —
     * up to 24 hours on a daily interval, with the staleness alert unable to evaluate throughout —
     * and a source that succeeds while this instance runs is re-asserted from the database rather
     * than from memory alone.
     *
     * **`last_success_at`, not `last_import_at`.** The latter is written on failure too, so it means
     * *last attempt*, and reading it published nothing for a source whose most recent run failed —
     * exactly when the staleness rule needs a value. A source that has never succeeded still
     * publishes no last-success: there is no true value, and a zero reads as 1970 to every rule
     * written on this gauge.
     *
     * **Every enabled source gets `has_succeeded` and `events_future` from the first tick, whether
     * or not it has ever worked** (#618, #700). A source with no series cannot be stale, late or
     * failing, only absent — and "0 sources stale" then agrees with "nothing is broken". It is also
     * why this loops over the sources and not over the query result: `countFuturePerSource` returns
     * no row for a source with no future events, which is precisely the broken one.
     *
     * One extra query per tick, grouped over `event` on `idx_event_event_source_id`, inside the same
     * `try` as the rest: an unwell database freezes the gauges rather than killing the scheduler.
     *
     * `days_since_future_event` is today minus the source's newest event date, floored at zero, so a
     * source holding a future event reads 0 and one whose programme ran out counts up from the day
     * it did (#1498). A source with no event at all counts from the day its row was created: it
     * has been quiet for exactly as long as it has existed, and a value it is, not an absence.
     */
    private suspend fun republishSourceState() {
        val today = LocalDate.now(clock)
        val rowsBySourceId = eventRepository.countFuturePerSource(today).toList().associateBy { it.eventSourceId }

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
                // Iterating the sources rather than the query result is the point: a source with no
                // future events has no row, and it is the one the alert exists for.
                val row = source.id?.let { rowsBySourceId[it] }
                metrics.publishFutureEvents(source.slug, row?.futureEvents ?: 0L)
                val quietSince = row?.newestEventDate ?: source.createdAt?.atZone(clock.zone)?.toLocalDate() ?: today
                metrics.publishDaysSinceFutureEvent(
                    source.slug,
                    days = maxOf(0L, ChronoUnit.DAYS.between(quietSince, today)),
                    knownQuiet = source.slug in KNOWN_QUIET_SOURCES
                )
            }
    }
}
