package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The passage of time, as this filter needs it: read the clock, and wait.
 *
 * **A seam for tests, and one abstraction rather than two on purpose (#408.)** Throttling is a
 * statement about time, so a test for it either virtualises time or measures the wall clock — and
 * measuring the wall clock is what made [PerHostThrottlingFilterTest] flaky on a loaded CI runner.
 * Reading the clock and waiting have to move together: two independent seams would let a test
 * advance the clock without waiting, or wait without advancing, and neither resembles reality.
 * Implementing both here means a test double where `wait` advances its own clock is automatically
 * consistent.
 */
internal interface ThrottleClock {
    fun markNow(): TimeMark

    suspend fun wait(duration: Duration)

    companion object {
        /** Real monotonic time and a real suspension — what production uses. */
        val SYSTEM: ThrottleClock =
            object : ThrottleClock {
                override fun markNow(): TimeMark = TimeSource.Monotonic.markNow()

                override suspend fun wait(duration: Duration) = delay(duration)
            }
    }
}

/**
 * WebClient [ExchangeFilterFunction] that enforces a politeness delay between
 * consecutive HTTP requests to the **same** host.
 *
 * Each host gets its own [Mutex] and monotonic timestamp so that requests to
 * different venues proceed concurrently, while requests to the same server are
 * spaced at least [politeDelayMillis] apart. This prevents overwhelming target
 * servers during web scraping — the throttling is transparent to callers and
 * applies automatically to every request made through the configured WebClient.
 *
 * Uses [kotlinx.coroutines.reactor.mono] to bridge between the reactive
 * [ExchangeFilterFunction] contract and coroutine-based [Mutex]/[delay].
 *
 * @param politeDelayMillis minimum time (in milliseconds) between consecutive
 *   requests to the same host. Requests arriving sooner will suspend until the
 *   delay has elapsed.
 * @param clock where time comes from. Defaults to real monotonic time; tests substitute a virtual
 *   one so they assert what this filter *decided* rather than how long they happened to take.
 */
class PerHostThrottlingFilter internal constructor(
    private val politeDelayMillis: Long,
    private val clock: ThrottleClock
) : ExchangeFilterFunction {
    /** The production constructor. `ThrottleClock` is internal, so this is what callers outside the module see. */
    constructor(politeDelayMillis: Long) : this(politeDelayMillis, ThrottleClock.SYSTEM)

    private val logger = KotlinLogging.logger {}

    /**
     * Per-host throttle state. Entries are created lazily on first access and
     * kept for the application lifetime (the set of target hosts is small and
     * bounded by the number of configured venues).
     */
    private val hostThrottles = ConcurrentHashMap<String, HostThrottle>()

    override fun filter(
        request: ClientRequest,
        next: ExchangeFunction
    ): Mono<ClientResponse> {
        val host = request.url().host ?: return next.exchange(request)

        // Bridge into a coroutine so we can use Mutex + delay, then
        // flatMap into the actual HTTP exchange which stays fully reactive.
        return mono { awaitThrottle(host) }
            .then(next.exchange(request))
    }

    /**
     * Acquires the per-host mutex and suspends if the elapsed time since
     * the last request to [host] is shorter than [politeDelayMillis].
     * Records the current timestamp before releasing the mutex so the
     * next caller sees the correct baseline.
     */
    private suspend fun awaitThrottle(host: String) {
        val throttle = hostThrottles.computeIfAbsent(host) { HostThrottle() }

        throttle.mutex.withLock {
            throttle.lastRequestMark?.let { mark ->
                val remaining = politeDelayMillis.milliseconds - mark.elapsedNow()
                if (remaining.isPositive()) {
                    logger.debug { "Throttling $host: waiting $remaining before next request" }
                    clock.wait(remaining)
                }
            }
            // Re-read the clock after waiting rather than reusing the pre-wait mark: the baseline
            // for the next caller is when THIS request went out, not when it started queueing.
            throttle.lastRequestMark = clock.markNow()
        }
    }
}

/**
 * Per-host throttle state holding a [Mutex] to serialize requests and
 * the [TimeMark] of the most recent request, read from the injected [ThrottleClock].
 */
private class HostThrottle {
    val mutex = Mutex()
    var lastRequestMark: TimeMark? = null
}
