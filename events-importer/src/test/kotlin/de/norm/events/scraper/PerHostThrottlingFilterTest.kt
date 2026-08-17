package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark

/**
 * Unit tests for [PerHostThrottlingFilter].
 *
 * **These assert what the filter decided, never how long the test took (#408.)**
 *
 * Every assertion here used to be `measureTimeMillis { … } shouldBeGreaterThanOrEqual 200`, and the
 * suite was flaky on a loaded CI runner — twice red on pull requests that touched no Kotlin at all.
 * The fragile part was never the assertion but the setup: a test that warms a host up and then
 * measures the next request assumes the throttle window is still open when the measurement starts,
 * and a GC pause or a preempted thread between those two statements lets it expire. The measured
 * value then comes back as ~0 rather than slightly under the bound, which is why **widening the
 * tolerance would have made it rarer without making it correct** — the failure is at the wrong end
 * of the range.
 *
 * So time is virtual here. [VirtualClock.waits] records exactly what the filter asked to wait for,
 * and waiting advances that same clock, so the sequence a real run would produce is reproduced
 * exactly and instantly. The suite no longer sleeps at all — it used to spend about 1.4 seconds
 * doing so.
 */
class PerHostThrottlingFilterTest {
    private val politeDelay = 200L

    /**
     * Virtual time for the filter: [wait] records the requested duration **and** advances the clock
     * by it, which is what a real suspension does. One object rather than a separate clock and
     * recorder, so the two can never disagree.
     */
    private class VirtualClock : ThrottleClock {
        private val source = TestTimeSource()

        /** Every duration the filter asked to wait for, in order. Empty means it never throttled. */
        val waits = mutableListOf<Duration>()

        override fun markNow(): TimeMark = source.markNow()

        override suspend fun wait(duration: Duration) {
            waits += duration
            source += duration
        }

        /** Simulates time passing between requests, without the filter having asked for it. */
        fun elapse(duration: Duration) {
            source += duration
        }

        private val start = source.markNow()

        /** How far the clock has moved in total — the timeline these requests would have produced. */
        fun totalElapsed(): Duration = start.elapsedNow()
    }

    private val clock = VirtualClock()
    private val filter = PerHostThrottlingFilter(politeDelay, clock)

    private val mockResponse: ClientResponse = mockk()
    private val mockExchange: ExchangeFunction =
        mockk {
            every { exchange(any()) } returns Mono.just(mockResponse)
        }

    /** Creates a minimal [ClientRequest] targeting the given [url]. */
    private fun request(url: String): ClientRequest = ClientRequest.create(HttpMethod.GET, URI.create(url)).build()

    /** Runs one request through the filter, exactly as the WebClient would. */
    private fun fetch(url: String) {
        filter.filter(request(url), mockExchange).block()
    }

    @Nested
    inner class SameHostThrottling {
        @Test
        fun `first request to a host is not delayed`() =
            runTest {
                fetch("https://example.com/page1")

                clock.waits.shouldContainExactly()
                verify(exactly = 1) { mockExchange.exchange(any()) }
            }

        @Test
        fun `consecutive requests to the same host wait the full politeness delay`() =
            runTest {
                fetch("https://example.com/page1")
                fetch("https://example.com/page2")

                // Exactly one wait, of exactly the configured length — not "at least", which is all
                // a wall-clock measurement could ever claim.
                clock.waits.shouldContainExactly(politeDelay.milliseconds)
                verify(exactly = 2) { mockExchange.exchange(any()) }
            }

        @Test
        fun `every subsequent request waits again, measured from the previous one`() =
            runTest {
                fetch("https://example.com/a")
                fetch("https://example.com/b")
                fetch("https://example.com/c")

                clock.waits.shouldContainExactly(politeDelay.milliseconds, politeDelay.milliseconds)
            }

        @Test
        fun `a request that arrives after the window has passed is not delayed`() =
            runTest {
                fetch("https://example.com/a")
                clock.elapse(politeDelay.milliseconds)

                fetch("https://example.com/b")

                clock.waits.shouldContainExactly()
            }

        @Test
        fun `a request arriving part-way through the window waits only the remainder`() =
            runTest {
                fetch("https://example.com/a")
                clock.elapse(50.milliseconds)

                fetch("https://example.com/b")

                // The property a wall-clock test could not check at all: not merely "it waited",
                // but that it waited the right amount.
                clock.waits.shouldContainExactly(150.milliseconds)
            }
    }

    @Nested
    inner class DifferentHostConcurrency {
        @Test
        fun `requests to different hosts are not delayed by each other`() =
            runTest {
                fetch("https://host-a.com/page")
                fetch("https://host-b.com/page")

                clock.waits.shouldContainExactly()
                verify(exactly = 2) { mockExchange.exchange(any()) }
            }

        @Test
        fun `each host maintains its own throttle independently`() =
            runTest {
                // The test that was flaky. It no longer depends on host A's window still being open
                // when the third request happens — the virtual clock only moves when something asks
                // it to, so there is no window to lose.
                fetch("https://host-b.com/1")
                fetch("https://host-a.com/1")

                fetch("https://host-a.com/2")

                clock.waits.shouldContainExactly(politeDelay.milliseconds)
                verify(exactly = 3) { mockExchange.exchange(any()) }
            }

        @Test
        fun `interleaving two hosts throttles each on its own timeline`() =
            runTest {
                fetch("https://host-a.com/1")
                fetch("https://host-b.com/1")
                fetch("https://host-a.com/2")
                fetch("https://host-b.com/2")

                // Only ONE wait, and host B is the one that escapes it. Host A's wait moved the clock
                // forward by the full delay, and B had been idle throughout — so by the time B's
                // second request arrives, its own window has already elapsed.
                //
                // Time passing counts for every host, whoever was waiting for it. That is precisely
                // the property two independent seams would get wrong, and the reason [ThrottleClock]
                // owns both reading the clock and waiting on it.
                clock.waits.shouldContainExactly(politeDelay.milliseconds)
            }
    }

    @Nested
    inner class ConcurrentSameHostRequests {
        @Test
        fun `three sequential requests produce a timeline of two delays`() =
            runTest {
                fetch("https://example.com/1")
                fetch("https://example.com/2")
                fetch("https://example.com/3")

                // The shape of the whole schedule, not just the individual waits.
                clock.totalElapsed() shouldBe (politeDelay * 2).milliseconds
            }

        // THE OLD `concurrent requests to the same host are serialized` TEST IS GONE, AND THAT IS A
        // DELIBERATE COVERAGE DECISION RATHER THAN AN OVERSIGHT.
        //
        // It asserted `measureTimeMillis { two concurrent requests } >= 2 * politeDelay`, which is
        // the same wall-clock pattern that made this file flaky. Replacing it in kind was tried
        // first: a real-clock version recording nanosecond timestamps at subscription and asserting
        // a minimum gap. That reasoning — "a loaded runner only makes gaps larger, so a minimum
        // bound is safe" — is wrong, and it failed 1 run in 15 locally with `49 should be >= 50`.
        // Millisecond truncation and the offset between the filter's own `TimeMark` and the test's
        // sampling point cost about a millisecond, entirely independent of load.
        //
        // What the Mutex guarantees is that the read-wait-write sequence for one host does not
        // interleave. That is **not observable from outside without measuring durations**: under a
        // virtual clock nothing suspends, so requests run sequentially whether or not the lock is
        // there, and both a correct and a lock-free implementation produce the same recorded waits
        // and the same total elapsed time. There is no signal to assert on.
        //
        // So it is covered by construction rather than by a test — the lock is held across the whole
        // critical section in `awaitThrottle`, which is visible in five lines of code — and the
        // observable consequence, the spacing between requests, is asserted exactly above. A test
        // that fails 7% of the time is worth less than an honest note about what is not checked.
    }

    @Nested
    inner class ZeroDelay {
        @Test
        fun `zero delay allows consecutive requests without throttling`() =
            runTest {
                val noDelayClock = VirtualClock()
                val noDelayFilter = PerHostThrottlingFilter(0, noDelayClock)

                noDelayFilter.filter(request("https://example.com/1"), mockExchange).block()
                noDelayFilter.filter(request("https://example.com/2"), mockExchange).block()

                noDelayClock.waits.shouldContainExactly()
            }
    }

    @Nested
    inner class DelegationToNext {
        @Test
        fun `filter delegates to the next exchange function and returns its response`() =
            runTest {
                val result = filter.filter(request("https://example.com/page"), mockExchange).block()

                result shouldBe mockResponse
                verify(exactly = 1) { mockExchange.exchange(any()) }
            }

        @Test
        fun `a request with no host is passed straight through`() =
            runTest {
                val result = filter.filter(request("mailto:someone@example.com"), mockExchange).block()

                result shouldBe mockResponse
                clock.waits.shouldContainExactly()
            }
    }
}
