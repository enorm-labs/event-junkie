package de.norm.events.scraper

import de.norm.events.promoter.UncreditedPromoterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Deletes the promoter rows no event credits, once a day (#2653). A name correction or a parser
 * fix mints the right row and leaves the old one public, with its slug, in the global search.
 * [UncreditedPromoterStore.deleteUncredited] says which rows stay.
 *
 * [ImportIdleGuard] holds the pass back while an import runs. The one-day [GRACE] is
 * [OrphanArtistSweep]'s, for the same reason: an import resolves a promoter before it links it.
 */
@Service
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class OrphanPromoterSweep(
    private val uncreditedPromoterStore: UncreditedPromoterStore,
    eventSourceRepository: EventSourceRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}
    private val idle = ImportIdleGuard(eventSourceRepository, "Orphan promoter sweep", logger)

    /** 03:40 UTC, after the orphan artist sweep and before the venue programme sweep. */
    @Scheduled(cron = $$"${app.promoters.orphan-sweep-cron:0 40 3 * * *}")
    @Suppress("TooGenericExceptionCaught") // Intentional: an uncaught exception cancels the task and shares the import scheduler
    suspend fun sweep() {
        try {
            runOnce()
        } catch (e: Exception) {
            logger.error(e) { "Orphan promoter sweep failed" }
        }
    }

    /** One pass: the slugs deleted, or `null` when an import was running and nothing ran. */
    suspend fun runOnce(): List<String>? =
        idle.runWhenIdle {
            val deleted = uncreditedPromoterStore.deleteUncredited(Instant.now(clock).minus(GRACE))
            logger.info { "Orphan promoter sweep deleted ${deleted.size} promoter(s) no event credits: ${deleted.joinToString()}" }
            deleted
        }

    private companion object {
        val GRACE: Duration = Duration.ofDays(1)
    }
}
