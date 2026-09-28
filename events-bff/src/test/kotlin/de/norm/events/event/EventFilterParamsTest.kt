package de.norm.events.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EventFilterParamsTest {
    @Test
    fun `two orders of the same event types are one filter, so they share a cache entry`() {
        val first = EventFilterParams(eventType = listOf("party", " CONCERT ", "PARTY", "")).toFilter()
        val second = EventFilterParams(eventType = listOf("CONCERT", "PARTY")).toFilter()

        first.eventTypes shouldBe listOf("CONCERT", "PARTY")
        first shouldBe second
    }

    @Test
    fun `no event type imposes no constraint`() {
        EventFilterParams().toFilter().eventTypes shouldBe emptyList()
    }
}
