package de.norm.events.venuecheck

import de.norm.events.scraper.LogFields
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.sync.Mutex
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** The counts of one pass, for its closing log line and the tests. */
data class SiteCheckSummary(
    val checked: Int,
    val ok: Int,
    val failed: Int,
    val skipped: Int,
    /** The part of [skipped] whose site answered 403 or 429. */
    val refused: Int,
    val needsReview: Int
)

/**
 * One pass of the site check over every venue without an importer (#2812).
 *
 * **An automated signal only puts a venue up for review.** A dead site is not a closed venue: RSO's domain
 * answered 404 and Funkloch's did not resolve while both were open. So the pass writes only `venue_site_check`,
 * and a person decides through the admin API whether to set `closed_on` (ADR-046) or `reviewed_at`.
 *
 * Venues are probed one after another. The scraper client throttles per host, and a pass of about 110 venues
 * once a month has no deadline worth the extra load of running them in parallel.
 */
@Service
class VenueSiteCheckService(
    private val store: VenueSiteCheckStore,
    private val prober: VenueSiteProber,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /** Held while a pass runs, so the schedule and the admin trigger never run two at once. */
    private val running = Mutex()

    /** Runs one pass, or returns `null` when a pass is already running. */
    @Suppress("TooGenericExceptionCaught") // A pass that fails is logged here; the callers only start it.
    suspend fun runOnce(): SiteCheckSummary? {
        if (!running.tryLock()) {
            logger.info { "Venue site check skipped: a pass is already running" }
            return null
        }
        return try {
            pass()
        } catch (e: Exception) {
            logger.error(e) { "Venue site check failed" }
            null
        } finally {
            running.unlock()
        }
    }

    /** The "needs review" list: a run of [REVIEW_THRESHOLD] failures that nobody reviewed since it began. */
    suspend fun needsReview(): List<NeedsReviewRow> = store.findNeedsReview(REVIEW_THRESHOLD)

    private suspend fun pass(): SiteCheckSummary {
        val candidates = store.findCandidates()
        logger.info { "Venue site check started: ${candidates.size} venue(s) without an importer" }
        var ok = 0
        var failed = 0
        var skipped = 0
        var refused = 0
        var needsReview = 0
        candidates.forEach { venue ->
            val result = prober.probeVenue(listOf(venue.websiteUrl, venue.programmeUrl))
            val failures = store.record(venue.venueId, result, Instant.now(clock))
            when {
                result.outcome == SiteOutcome.OK -> ok++
                result.outcome == SiteOutcome.SKIPPED -> skipped++
                else -> failed++
            }
            if (result.isRefused) {
                refused++
                logger.at(Level.INFO) {
                    message = "Venue site check: ${venue.slug} refused the probe with ${result.httpStatus}, skipped"
                    payload = mapOf(LogFields.URL to result.url, LogFields.HTTP_STATUS to result.httpStatus)
                }
            }
            if (result.outcome.isFailure && failures >= REVIEW_THRESHOLD) {
                needsReview++
                logger.at(Level.WARN) {
                    message = "Venue site check: ${venue.slug} failed $failures times in a row (${result.outcome}), needs review"
                    payload = listOfNotNull(LogFields.URL to result.url, result.httpStatus?.let { LogFields.HTTP_STATUS to it }).toMap()
                }
            }
        }
        val summary = SiteCheckSummary(candidates.size, ok, failed, skipped, refused, needsReview)
        logger.info {
            "Venue site check finished: ${summary.checked} checked, ${summary.ok} ok, ${summary.failed} failed, " +
                "${summary.skipped} skipped (${summary.refused} refused with 403 or 429), " +
                "${summary.needsReview} at $REVIEW_THRESHOLD or more failures in a row"
        }
        return summary
    }

    companion object {
        /** Three monthly failures in a row: a quarter in which the site never answered. */
        const val REVIEW_THRESHOLD = 3
    }
}
