package de.norm.events.enrichment

import de.norm.events.artist.ArtistEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/** What one lookup pass of an [ArtistLookupSweep] tick owed and stored; [OFF] for a switched-off lookup. */
data class LookupPass(
    val owed: Int,
    val stored: Int
) {
    override fun toString() = if (owed < 0) "off" else "$stored of $owed"

    /** What one row of a pass came to. */
    sealed interface Row {
        /** Read and stored. */
        data object Stored : Row

        /** The service would not answer for this row; it waits for the next tick. */
        data class Failed(
            val cause: Exception
        ) : Row

        /** The service switched itself off mid-pass; this row and the rest wait for the next tick. */
        data object Stop : Row
    }

    companion object {
        val OFF = LookupPass(owed = -1, stored = -1)

        private const val STOP_AFTER_CONSECUTIVE_FAILURES = 3

        /**
         * Runs [read] over [candidates] in order. The pass stops after [STOP_AFTER_CONSECUTIVE_FAILURES]
         * failed rows in a row, which is an outage rather than a burst (#1610), on a [Row.Stop], or at
         * [deadline]. A failed row is counted and left for the next tick.
         */
        suspend fun drain(
            pass: String,
            candidates: List<ArtistEntity>,
            deadline: TimeMark,
            read: suspend (ArtistEntity) -> Row
        ): LookupPass {
            if (candidates.isEmpty()) return LookupPass(owed = 0, stored = 0)
            var stored = 0
            var consecutiveFailures = 0
            var stopped = false
            for ((done, artist) in candidates.withIndex()) {
                if (stopped ||
                    consecutiveFailures >= STOP_AFTER_CONSECUTIVE_FAILURES ||
                    budgetSpent(deadline, pass, done, candidates.size)
                ) {
                    break
                }
                when (val row = read(artist)) {
                    Row.Stored -> {
                        stored++
                        consecutiveFailures = 0
                    }

                    is Row.Failed -> {
                        consecutiveFailures++
                        logger.warn(row.cause) { "$pass failed for '${artist.name}' ($consecutiveFailures in a row)" }
                    }

                    Row.Stop -> {
                        stopped = true
                    }
                }
            }
            logger.info { "$pass stored $stored of ${candidates.size} owed" }
            return LookupPass(owed = candidates.size, stored = stored)
        }

        /** A deadline that never passes: a pass called without a budget. */
        fun noDeadline(): TimeMark = TimeSource.Monotonic.markNow() + Duration.INFINITE

        /**
         * Whether [deadline] has passed, checked before each row. In a brownout some rows still
         * succeed, so the stop after three failures in a row never comes and only time ends the
         * pass (#2896). Logs the stop, so a slow tick is told from a stopped one.
         */
        fun budgetSpent(
            deadline: TimeMark,
            pass: String,
            done: Int,
            owed: Int
        ): Boolean {
            if (!deadline.hasPassedNow()) return false
            logger.warn { "$pass stopped at its time budget after $done of $owed row(s); the rest wait for the next tick" }
            return true
        }
    }
}
