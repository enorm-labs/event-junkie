package de.norm.events.event

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `bff.events.served`.
 *
 * The names and tag values are asserted as literal strings on purpose: the dashboards in
 * `docs/ops/PLATFORM_SETUP.md` §7 are written against them and nothing checks the two agree, so a
 * rename that looks like a tidy-up is a silently dead panel. Referring to the constants would make
 * this pass through exactly the change it exists to catch.
 */
class BffMetricsTest {
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: BffMetrics

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = BffMetrics(registry)
    }

    private fun served(endpoint: String) =
        registry
            .find("bff.events.served")
            .tag("endpoint", endpoint)
            .counter()
            ?.count()

    @Test
    fun `each endpoint gets its own series`() {
        metrics.recordServed(BffMetrics.ENDPOINT_SEARCH, 20)
        metrics.recordServed(BffMetrics.ENDPOINT_TODAY, 7)
        metrics.recordServed(BffMetrics.ENDPOINT_CALENDAR, 130)
        metrics.recordServed(BffMetrics.ENDPOINT_DETAIL, 1)

        served("search") shouldBe 20.0
        served("today") shouldBe 7.0
        served("calendar") shouldBe 130.0
        served("detail") shouldBe 1.0
    }

    @Test
    fun `it counts events rather than requests, so repeated calls accumulate the page sizes`() {
        metrics.recordServed(BffMetrics.ENDPOINT_SEARCH, 20)
        metrics.recordServed(BffMetrics.ENDPOINT_SEARCH, 20)
        metrics.recordServed(BffMetrics.ENDPOINT_SEARCH, 3)

        // 43, not 3: `http.server.requests` already counts requests for free, and duplicating it here
        // would add nothing. What no free meter can say is how much data went out.
        served("search") shouldBe 43.0
    }

    /**
     * Zero is the interesting value on this meter — an endpoint being called and returning nothing is
     * exactly the state worth alerting on — so the series has to exist rather than be skipped, or
     * "returning nothing" would be indistinguishable from "nobody is calling it".
     */
    @Test
    fun `an empty response still creates the series`() {
        metrics.recordServed(BffMetrics.ENDPOINT_TODAY, 0)

        served("today") shouldBe 0.0
    }
}
