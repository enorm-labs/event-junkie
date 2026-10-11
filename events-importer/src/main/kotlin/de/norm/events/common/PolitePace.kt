package de.norm.events.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One request at a time, [gap] apart, process-wide: the pace an open API asks to be read at.
 *
 * Two sweeps running for two sources queue on the one mutex rather than each keeping a timer. The
 * `musicbrainz`, `discogs` and `wikimedia` clients pace themselves through this rather than through
 * the `scraper` module's `PerHostThrottlingFilter`, because depending on `scraper` from a client
 * `scraper` calls would be a cycle.
 */
class PolitePace(
    private val gap: Duration
) {
    private val mutex = Mutex()
    private var lastRequest: TimeMark? = null

    /** The result of [request], run after the wait that keeps it [gap] behind the one before it. */
    suspend fun <T> next(request: suspend () -> T): T =
        mutex.withLock {
            val since = lastRequest?.elapsedNow()
            if (since != null && since < gap) delay(gap - since)
            lastRequest = TimeSource.Monotonic.markNow()
            request()
        }
}
