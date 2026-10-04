package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** `eventType` repeats, and an event of any given type matches (#1995). */
class EventTypeFilterTest : BaseControllerTest() {
    @Test
    fun `GET events filters by several event types, any of them matching`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Gig", "gig", LocalDate.now(ClockConfiguration.BERLIN), eventType = "CONCERT")
            insertEvent(venueId, "Rave", "rave", LocalDate.now(ClockConfiguration.BERLIN).plusDays(1), eventType = "PARTY")
            insertEvent(venueId, "Quiz", "quiz", LocalDate.now(ClockConfiguration.BERLIN).plusDays(2), eventType = "QUIZ")

            // One value, repeated values, the comma form and mixed case all bind to the same list.
            mapOf(
                "eventType=concert" to listOf("gig"),
                "eventType=CONCERT&eventType=PARTY" to listOf("gig", "rave"),
                "eventType=PARTY,concert" to listOf("gig", "rave"),
                "eventType=PARTY&eventType=UNKNOWN" to listOf("rave"),
                "eventType=UNKNOWN" to emptyList()
            ).forEach { (query, slugs) ->
                val body =
                    webTestClient
                        .get()
                        .uri("/events?$query")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody(Map::class.java)
                        .returnResult()
                        .responseBody

                @Suppress("UNCHECKED_CAST")
                val content = body?.get("content") as List<Map<String, Any?>>
                content.map { it["slug"] } shouldContainExactlyInAnyOrder slugs
            }
        }

    @Test
    fun `GET events calendar filters by several event types`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Gig", "gig", LocalDate.now(ClockConfiguration.BERLIN).plusDays(1), eventType = "CONCERT")
            insertEvent(venueId, "Rave", "rave", LocalDate.now(ClockConfiguration.BERLIN).plusDays(2), eventType = "PARTY")
            insertEvent(venueId, "Quiz", "quiz", LocalDate.now(ClockConfiguration.BERLIN).plusDays(3), eventType = "QUIZ")

            webTestClient
                .get()
                .uri(
                    "/events/calendar?from=${LocalDate.now(
                        ClockConfiguration.BERLIN
                    )}&to=${LocalDate.now(ClockConfiguration.BERLIN).plusDays(7)}&eventType=QUIZ&eventType=CONCERT"
                ).exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].slug")
                .isEqualTo("gig")
                .jsonPath("$[1].slug")
                .isEqualTo("quiz")
        }
}
