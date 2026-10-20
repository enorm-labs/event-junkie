package de.norm.events.enrichment

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

    companion object {
        val OFF = LookupPass(owed = -1, stored = -1)

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
