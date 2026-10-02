package de.norm.events.scraper

import de.norm.events.venue.VenueProgrammeStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * Re-derives every venue's genre families and event types once a day (#327). Each import already
 * refreshes its own venue; this pass covers the window moving on, hand-made events and a genre
 * family remap by `GenreFamilyReconciler`.
 *
 * A pass while any import runs does nothing, so it never reads a venue's events mid-upsert.
 */
@Service
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class VenueProgrammeSweep(
    private val venueProgrammeStore: VenueProgrammeStore,
    private val eventSourceRepository: EventSourceRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /** 03:45 UTC, after the orphan artist sweep, while no scheduled import is likely to run. */
    @Scheduled(cron = $$"${app.venues.programme-sweep-cron:0 45 3 * * *}")
    @Suppress("TooGenericExceptionCaught") // Intentional: an uncaught exception cancels the task and shares the import scheduler
    suspend fun sweep() {
        try {
            runOnce()
        } catch (e: Exception) {
            logger.error(e) { "Venue programme sweep failed" }
        }
    }

    /** One pass: the number of venue rows changed, or `null` when an import was running and nothing ran. */
    suspend fun runOnce(): Long? {
        val running = eventSourceRepository.countByStatus(ImportStatus.RUNNING.name)
        if (running > 0) {
            logger.info { "Venue programme sweep skipped: $running import(s) running" }
            return null
        }
        logger.info { "Venue programme sweep started" }
        val changed = venueProgrammeStore.refreshAll(LocalDate.now(clock).minusDays(VenueProgrammeStore.WINDOW_DAYS))
        logger.info { "Venue programme sweep changed $changed venue(s)" }
        return changed
    }
}
