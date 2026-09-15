package de.norm.events.event

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The events search binds `q` into an `ILIKE` pattern, and `%` and `_` in a visitor's term are
 * letters, not wildcards (#1456). `/events/calendar` applies the same filter through the same code.
 */
class EventSearchQueryTest : BaseControllerTest() {
    @Test
    fun `GET events treats a wildcard in the query as a letter`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "100% Live", "100-live", LocalDate.now())
            insertEvent(venueId, "Goodlive", "goodlive", LocalDate.now())
            insertEvent(venueId, "a_b", "a-b", LocalDate.now())
            insertEvent(venueId, "axb", "axb", LocalDate.now())

            webTestClient
                .get()
                .uri("/events?q={q}", "%")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("100-live")

            webTestClient
                .get()
                .uri("/events?q={q}", "_")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("a-b")
        }
}
