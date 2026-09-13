package de.norm.events.event

import com.jayway.jsonpath.JsonPath
import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The order the event endpoints hand out, through HTTP: the daily-seeded tiebreak (#1380) and the
 * assumed start a timeless event sorts and shows by (#1384). `EventSearchRepositoryTest` holds the
 * SQL-level cases; these are the ones that need the controller and the cache in the loop.
 */
class EventOrderingControllerTest : BaseControllerTest() {
    @Test
    fun `GET events today orders a tie the way the list does`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val today = LocalDate.now()
            repeat(6) { insertEvent(venueId, "Night $it", "night-$it", today, startTime = LocalTime.of(23, 0)) }

            val tonight = slugsOf("/events/today", "$[*].slug")
            val list = slugsOf("/events?from=$today&to=$today", "$.content[*].slug")

            tonight shouldBe list
            tonight.toSet().size shouldBe 6
        }

    @Test
    fun `GET events gives every visitor the same order for a tie`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val date = LocalDate.now().plusDays(1)
            repeat(6) { insertEvent(venueId, "Night $it", "night-$it", date, startTime = LocalTime.of(23, 0)) }

            val first = slugsOf("/events", "$.content[*].slug")
            // Past the cache, so this is the database answering twice rather than the cache once.
            responseCache.clear()
            val second = slugsOf("/events", "$.content[*].slug")

            first shouldBe second
        }

    private fun slugsOf(
        uri: String,
        jsonPath: String
    ): List<String> {
        val body =
            webTestClient
                .get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        return JsonPath.read(body, jsonPath)
    }

    @Test
    fun `GET events carries the assumed start only where the venue published no time`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val date = LocalDate.now().plusDays(1)
            insertEvent(venueId, "Timed", "timed", date, startTime = LocalTime.of(20, 0))
            insertEvent(venueId, "Doors only", "doors-only", date, doorsTime = LocalTime.of(19, 0))
            insertEvent(venueId, "Timeless", "timeless", date, eventType = "PARTY")

            webTestClient
                .get()
                .uri("/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                // Sorted by effective start: doors 19:00, start 20:00, the party's 23:00 slot.
                .jsonPath("$.content[0].slug")
                .isEqualTo("doors-only")
                .jsonPath("$.content[0].assumedStartTime")
                .isEmpty
                .jsonPath("$.content[1].slug")
                .isEqualTo("timed")
                .jsonPath("$.content[1].assumedStartTime")
                .isEmpty
                .jsonPath("$.content[2].slug")
                .isEqualTo("timeless")
                .jsonPath("$.content[2].assumedStartTime")
                .isEqualTo("23:00:00")
        }
}
