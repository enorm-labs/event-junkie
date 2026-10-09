package de.norm.events.event

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    @Test
    fun `two orders of the same venue types are one filter`() {
        val first = EventFilterParams(venueType = listOf("club", " bar", "club", "")).toFilter()
        val second = EventFilterParams(venueType = listOf("bar", "club")).toFilter()

        first.venueTypes shouldBe listOf("bar", "club")
        first shouldBe second
    }

    @Test
    fun `two orders and cases of the same times of day are one filter`() {
        val first = EventFilterParams(timeOfDay = listOf("late", " Daytime", "LATE", "")).toFilter()
        val second = EventFilterParams(timeOfDay = listOf("daytime", "late")).toFilter()

        first.timesOfDay shouldBe listOf("daytime", "late")
        first shouldBe second
    }

    @Test
    fun `two orders and cases of the same languages are one filter, and two languages are two`() {
        val first = EventFilterParams(language = listOf("en", " DE", "EN", "")).toFilter()
        val second = EventFilterParams(language = listOf("de", "en")).toFilter()

        first.spokenLanguages shouldBe listOf("de", "en")
        first shouldBe second
        EventFilterParams(language = listOf("en")).toFilter() shouldNotBe EventFilterParams(language = listOf("de")).toFilter()
    }
}
