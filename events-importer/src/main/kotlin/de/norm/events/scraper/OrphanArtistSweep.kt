package de.norm.events.scraper

import de.norm.events.artist.UnbilledArtistStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Deletes the artist rows no event bills, once a day (#350). A name-rule fix mints the corrected
 * row and leaves the old one with its public page and its slug, and so does an event the venue
 * withdraws. [UnbilledArtistStore.deleteUnbilled] says which rows stay.
 *
 * [ImportIdleGuard] holds the pass back while an import runs. The one-day [GRACE] covers a row
 * minted by a run that has not linked it yet.
 */
@Service
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class OrphanArtistSweep(
    private val unbilledArtistStore: UnbilledArtistStore,
    eventSourceRepository: EventSourceRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}
    private val idle = ImportIdleGuard(eventSourceRepository, "Orphan artist sweep", logger)

    /** 03:30 UTC, after the data-quality snapshot, so that day's snapshot still counts these rows. */
    @Scheduled(cron = $$"${app.artists.orphan-sweep-cron:0 30 3 * * *}")
    @Suppress("TooGenericExceptionCaught") // Intentional: an uncaught exception cancels the task and shares the import scheduler
    suspend fun sweep() {
        try {
            runOnce()
        } catch (e: Exception) {
            logger.error(e) { "Orphan artist sweep failed" }
        }
    }

    /** One pass: the number of rows deleted, or `null` when an import was running and nothing ran. */
    suspend fun runOnce(): Long? =
        idle.runWhenIdle {
            val deleted = unbilledArtistStore.deleteUnbilled(Instant.now(clock).minus(GRACE))
            logger.info { "Orphan artist sweep deleted $deleted artist(s) no event bills" }
            deleted
        }

    private companion object {
        val GRACE: Duration = Duration.ofDays(1)
    }
}
