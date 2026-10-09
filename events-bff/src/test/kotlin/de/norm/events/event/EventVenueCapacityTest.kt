package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/** An event's embedded venue carries its capacity, so the home page can pick a small room in the browser (#2722). */
class EventVenueCapacityTest : BaseControllerTest() {
    @Test
    fun `the embedded venue carries its capacity, and null where none is known`(): Unit =
        runBlocking {
            val today = LocalDate.now(ClockConfiguration.BERLIN)
            val small = insertVenue("Schokoladen", "schokoladen")
            val unknown = insertVenue("Somewhere", "somewhere")
            databaseClient.sql("UPDATE events.venue SET capacity = 120 WHERE id = $small").await()
            insertEvent(small, "Small Gig", "small-gig", today)
            insertEvent(unknown, "Unknown Gig", "unknown-gig", today)

            listOf("/events", "/events/today").forEach { uri ->
                val root = if (uri == "/events") "$.content" else "$"
                webTestClient
                    .get()
                    .uri(uri)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$root[?(@.slug == 'small-gig')].venue.capacity")
                    .isEqualTo(listOf(120))
                    .jsonPath("$root[?(@.slug == 'unknown-gig')].venue.capacity")
                    .isEqualTo(listOf(null))
            }
        }
}
