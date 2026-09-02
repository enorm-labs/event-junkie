package de.norm.events.event

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EventStatusTest {
    @Test
    fun `parseOrDefault returns SCHEDULED for exact match`() {
        EventStatus.parseOrDefault("SCHEDULED") shouldBe EventStatus.SCHEDULED
    }

    @Test
    fun `parseOrDefault is case-insensitive`() {
        EventStatus.parseOrDefault("scheduled") shouldBe EventStatus.SCHEDULED
        EventStatus.parseOrDefault("Relocated") shouldBe EventStatus.RELOCATED
        EventStatus.parseOrDefault("cAnCeLlEd") shouldBe EventStatus.CANCELLED
    }

    @Test
    fun `parseOrDefault trims whitespace`() {
        EventStatus.parseOrDefault("  POSTPONED  ") shouldBe EventStatus.POSTPONED
        EventStatus.parseOrDefault("\tCANCELLED\n") shouldBe EventStatus.CANCELLED
    }

    @Test
    fun `parseOrDefault returns all valid enum values`() {
        EventStatus.parseOrDefault("SCHEDULED") shouldBe EventStatus.SCHEDULED
        EventStatus.parseOrDefault("RELOCATED") shouldBe EventStatus.RELOCATED
        EventStatus.parseOrDefault("CANCELLED") shouldBe EventStatus.CANCELLED
        EventStatus.parseOrDefault("POSTPONED") shouldBe EventStatus.POSTPONED
    }

    @Test
    fun `parseOrDefault returns SCHEDULED for unknown values`() {
        EventStatus.parseOrDefault("UNKNOWN") shouldBe EventStatus.SCHEDULED
        EventStatus.parseOrDefault("verlegt") shouldBe EventStatus.SCHEDULED
        EventStatus.parseOrDefault("") shouldBe EventStatus.SCHEDULED
    }
}
