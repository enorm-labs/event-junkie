package de.norm.events.importing

import io.github.oshai.kotlinlogging.KLogger

/**
 * Runs a scheduled sweep's pass only while no import runs, and logs both outcomes under [name].
 *
 * An import resolves a row before it links it, so a sweep between the two would fail that run.
 */
class ImportIdleGuard(
    private val eventSourceRepository: EventSourceRepository,
    private val name: String,
    private val logger: KLogger
) {
    /** The pass's result, or `null` when an import was running and the pass did not run. */
    suspend fun <T : Any> runWhenIdle(pass: suspend () -> T): T? {
        val running = eventSourceRepository.countByStatus(ImportStatus.RUNNING.name)
        if (running > 0) {
            logger.info { "$name skipped: $running import(s) running" }
            return null
        }
        logger.info { "$name started" }
        return pass()
    }
}
