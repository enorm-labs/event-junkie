package de.norm.events

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/**
 * Asserts the caching path that ships, rather than the component in isolation (#269).
 *
 * The decisive test is the one that deletes the rows and asks again: a response that survives its
 * own data disappearing came from memory, and nothing else explains it.
 */
@DisplayName("Response caching")
class ResponseCachingIntegrationTest : BaseControllerTest() {
    @Test
    fun `serves a response whose rows have since been deleted, until the cache is emptied`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Today Show", "today-show", LocalDate.now())

            expectTodayCount(1)

            databaseClient.sql("DELETE FROM events.event").await()

            // Still one. The database has nothing left to give, so this is the cached response.
            expectTodayCount(1)

            responseCache.clear()
            expectTodayCount(0)
        }

    @Test
    fun `keeps two endpoints apart when they are asked for the same slug`(): Unit =
        runBlocking {
            // The collision a cache keyed on one shared string would have. Both keys carry "astra";
            // only their types differ.
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Astra Show", "astra", LocalDate.now())

            webTestClient
                .get()
                .uri("/events/astra")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Astra Show")

            webTestClient
                .get()
                .uri("/venues/astra")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("Astra")
        }

    @Test
    fun `marks a read response cacheable for the configured lifetime`(): Unit =
        runBlocking {
            webTestClient
                .get()
                .uri("/events/today")
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=60, public")
        }

    @Test
    fun `never lets a not-found answer be cached`(): Unit =
        runBlocking {
            // A slug that does not resolve today may resolve tomorrow, and a heuristically cached
            // 404 would keep reporting an event as missing after it was published.
            webTestClient
                .get()
                .uri("/events/no-such-event")
                .exchange()
                .expectStatus()
                .isNotFound
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        }

    @Test
    fun `leaves the actuator alone`(): Unit =
        runBlocking {
            rootClient
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectHeader()
                .doesNotExist(HttpHeaders.CACHE_CONTROL)
        }

    private fun expectTodayCount(expected: Int) {
        webTestClient
            .get()
            .uri("/events/today")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(expected)
    }
}
