package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Keeps the image cache's gauges current, because a gauge here cannot fetch its own value.
 *
 * The reasoning is [MetricsRefreshService][de.norm.events.scraper.MetricsRefreshService]'s and is not
 * repeated: Micrometer reads a gauge synchronously on the thread serving `/actuator/prometheus`, and
 * every query this module can make suspends.
 *
 * **Its own service rather than two more lines in that one**, because `scraper` may not reach into
 * `image` — the dependency points this way ([ImageModule]) and reversing it would be a cycle. It
 * shares the interval property, so the two sets of gauges are stale by the same amount.
 *
 * **The sweep's gauges are deliberately not refreshed here.** They describe what the last sweep
 * found, and that pass runs every six hours; re-deriving them every minute would need the whole
 * bucket listing each time to say the same number.
 */
@Service
class ImageMetricsRefreshService(
    private val repository: CachedImageRepository,
    private val imgproxyProperties: ImgproxyProperties,
    private val metrics: ImageCacheMetrics
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Refreshes both polled gauges: the URL states, and the derivative backlog.
     *
     * `fixedDelayString` rather than `fixedRate`, so a slow database does not queue queries against
     * the database that is already slow.
     *
     * **Failures are caught, not propagated**, exactly as in the scraper's refresher: an uncaught
     * exception cancels a `@Scheduled` task for the life of the process, and this scheduler is the
     * one that runs the image passes. A frozen gauge is detectable; a dead scheduler stops fetching.
     */
    @Scheduled(fixedDelayString = $$"${app.metrics.refresh-interval-ms:60000}")
    @Suppress("TooGenericExceptionCaught") // Intentional: monitoring must not be able to fail the application
    suspend fun refreshGauges() {
        try {
            metrics.updateUrlStates(repository.countUrlStates())
            metrics.updateDerivativeBacklog(repository.countNeedingDerivatives(imgproxyProperties.expectedVariants))
        } catch (e: Exception) {
            logger.warn(e) { "Could not refresh the image cache gauges; they keep their previous values" }
        }
    }
}
