package de.norm.events.common

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Instant

class LastSuccessGaugeTest {
    private val registry = SimpleMeterRegistry()
    private val gauge = LastSuccessGauge(registry, NAME)

    private fun value() = registry.get(NAME).gauge().value()

    @Test
    fun `the gauge reads 0 before the first pass`() {
        value() shouldBe 0.0
    }

    @Test
    fun `a pass stamps its epoch second, also an earlier one`() {
        gauge.markSucceeded(LATER)
        gauge.markSucceeded(EARLIER)

        value() shouldBe EARLIER.epochSecond.toDouble()
    }

    @Test
    fun `a seed keeps a pass that finished first`() {
        gauge.markSucceeded(LATER)
        gauge.seed(EARLIER)

        value() shouldBe LATER.epochSecond.toDouble()
    }

    @Test
    fun `a seed starts the age`() {
        gauge.seed(EARLIER)

        value() shouldBe EARLIER.epochSecond.toDouble()
    }

    private companion object {
        const val NAME = "test.pass.last_success"
        val EARLIER: Instant = Instant.parse("2026-11-01T03:00:00Z")
        val LATER: Instant = Instant.parse("2026-11-02T03:00:00Z")
    }
}
