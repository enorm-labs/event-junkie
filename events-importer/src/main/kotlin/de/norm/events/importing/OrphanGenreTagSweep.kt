package de.norm.events.importing

import de.norm.events.common.rethrowIfCancelled
import de.norm.events.genretag.UnlinkedGenreTagStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Deletes the genre tags no event links, once a day (#4923). A synonym change or a parser fix moves an event to
 * another tag and leaves the old one listed in `/api/genres`. [UnlinkedGenreTagStore.deleteUnlinked] says which
 * tags stay.
 *
 * [ImportIdleGuard] holds the pass back while an import runs. The one-day [GRACE] is [OrphanPromoterSweep]'s,
 * for the same reason: an import creates a tag before it links it.
 */
@Service
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class OrphanGenreTagSweep(
    private val unlinkedGenreTagStore: UnlinkedGenreTagStore,
    eventSourceRepository: EventSourceRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}
    private val idle = ImportIdleGuard(eventSourceRepository, "Orphan genre tag sweep", logger)

    /** 03:50 UTC, after the venue programme sweep. */
    @Scheduled(cron = $$"${app.genre-tags.orphan-sweep-cron:0 50 3 * * *}")
    // Intentional: an uncaught exception cancels the task and shares the import scheduler
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    suspend fun sweep() {
        try {
            runOnce()
        } catch (e: Exception) {
            rethrowIfCancelled()
            logger.error(e) { "Orphan genre tag sweep failed" }
        }
    }

    /** One pass: the slugs deleted, or `null` when an import was running and nothing ran. */
    suspend fun runOnce(): List<String>? =
        idle.runWhenIdle {
            val deleted = unlinkedGenreTagStore.deleteUnlinked(Instant.now(clock).minus(GRACE))
            logger.info { "Orphan genre tag sweep deleted ${deleted.size} genre tag(s) no event links: ${deleted.joinToString()}" }
            deleted
        }

    private companion object {
        val GRACE: Duration = Duration.ofDays(1)
    }
}
