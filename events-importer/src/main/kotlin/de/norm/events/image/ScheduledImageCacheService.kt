package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Runs the image cache pass on its own tick.
 *
 * **Separate from the import pipeline deliberately.** Fetching an image inside the transaction that
 * writes events would hold that transaction open across the network, and it would tie a venue's
 * image being slow to its events failing to import. The two also want different cadences: events
 * change daily, an image at a URL almost never does.
 *
 * The pass is a no-op while `app.images.fetch-enabled` is false, which is the default.
 */
@Service
class ScheduledImageCacheService(
    private val imageCacheService: ImageCacheService,
    private val derivativeService: ImageDerivativeService
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${app.images.tick-millis:300000}")
    fun tick() {
        // Nothing here may throw: an uncaught exception silently cancels a @Scheduled task for the
        // life of the process, and the next symptom is images that stopped updating weeks ago.
        runCatching {
            runBlocking {
                // Fetch first, then derive. A derivative needs an original, so deriving first would
                // simply find nothing on the pass that fetched it.
                imageCacheService.refreshBatch()
                derivativeService.generateBatch()
            }
        }.onFailure { logger.error(it) { "Image cache pass failed" } }
    }
}
