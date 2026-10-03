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

    @Test
    fun `two orders of the same families are one filter`() {
        val first = EventFilterParams(family = listOf("hip-hop", " electronic", "hip-hop", "")).toFilter()
        val second = EventFilterParams(family = listOf("electronic", "hip-hop")).toFilter()

        first.familySlugs shouldBe listOf("electronic", "hip-hop")
        first shouldBe second
    }

    @Test
    fun `two orders of the same districts are one filter`() {
        val first = EventFilterParams(district = listOf("neukoelln", " kreuzberg", "neukoelln", "")).toFilter()
        val second = EventFilterParams(district = listOf("kreuzberg", "neukoelln")).toFilter()

        first.districts shouldBe listOf("kreuzberg", "neukoelln")
        first shouldBe second
    }
}
