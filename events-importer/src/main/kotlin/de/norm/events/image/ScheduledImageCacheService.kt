package de.norm.events.image

import de.norm.events.common.runScheduled
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val derivativeService: ImageDerivativeService,
    private val removalService: ImageRemovalService
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${app.images.tick-millis:300000}")
    suspend fun tick() =
        runScheduled("Image cache pass", logger) {
            // Fetch first, then derive. A derivative needs an original, so deriving first would
            // simply find nothing on the pass that fetched it.
            imageCacheService.refreshBatch()
            derivativeService.generateBatch()
        }

    /**
     * The orphan sweep, on a much slower tick than the fetch.
     *
     * Six hours because it lists the whole bucket, and because nothing it deletes is urgent — the
     * takedown route is what answers a venue now. Running it every five minutes would spend a
     * listing to find nothing, most times.
     */
    @Scheduled(fixedDelayString = "\${app.images.sweep.tick-millis:21600000}")
    suspend fun sweepTick() = runScheduled("Image sweep", logger) { removalService.sweep() }
}
