package de.norm.events.account

import de.norm.events.common.BERLIN
import de.norm.events.common.rethrowIfCancelled
import de.norm.events.musicbrainz.MusicBrainzSearch
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

private val logger = KotlinLogging.logger {}

/**
 * Moves each watch whose MusicBrainz id MusicBrainz merged into another to the survivor (#5225). The importer gives the
 * act's artist row the survivor's id, so a watch on the old id would never convert in [WatchConversionJob].
 *
 * Every replica runs it, and [WatchStore.claimMonth] hands each Berlin month to one of them. The month is claimed before
 * MusicBrainz is read, so MusicBrainz is read once a month. [MusicBrainzSearch.artist] keeps its pace and follows the
 * redirect a merged id answers with. An id MusicBrainz does not know (deleted, not merged) keeps its watch and is counted.
 * MusicBrainz unavailable ends the run: what moved stays moved, and the next month reads every id again.
 * One line per run: ids read, watches moved, unknown ids, and the failure if there is one.
 */
@Component
class WatchMergeJob(
    private val watches: WatchStore,
    private val musicBrainz: MusicBrainzSearch,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @Scheduled(fixedDelayString = "\${app.watch.merge-check-delay}", initialDelayString = "\${app.watch.merge-check-initial-delay}")
    // Any other failure ends this run with a WARN. The month is claimed, and the next month tries again.
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    suspend fun run() {
        try {
            val month = LocalDate.now(clock.withZone(BERLIN)).withDayOfMonth(1)
            if (!watches.claimMonth(month)) {
                logger.debug { "Watch merge check: $month is claimed already" }
                return
            }
            check(month)
        } catch (e: Exception) {
            rethrowIfCancelled()
            logger.warn(e) { "Watch merge check failed" }
        }
    }

    private suspend fun check(month: LocalDate) {
        val ids = watches.ids()
        var read = 0
        var moved = 0L
        var unknown = 0
        try {
            for (id in ids) {
                val artist = musicBrainz.artist(id)
                read++
                when {
                    artist == null -> unknown++
                    artist.id != id -> moved += watches.move(id, artist.id, artist.name.take(WatchController.MAX_NAME))
                }
            }
        } catch (e: MusicBrainzUnavailableException) {
            logger.warn(e) { "Watch merge check for ${YearMonth.from(month)} stopped: ${summary(ids.size, read, moved, unknown)}" }
            return
        } finally {
            metrics.record(WatchAction.MOVED, moved)
        }
        logger.info { "Watch merge check for ${YearMonth.from(month)}: ${summary(ids.size, read, moved, unknown)}" }
    }

    private fun summary(
        total: Int,
        read: Int,
        moved: Long,
        unknown: Int
    ) = "$read of $total ids read, $moved watches moved, $unknown ids unknown to MusicBrainz"
}
