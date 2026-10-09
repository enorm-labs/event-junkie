package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/** `GET /events/{slug}` carries what moved on the event in the last 14 days, newest first (#2725). */
class EventChangesTest : BaseControllerTest() {
    private val today: LocalDate = LocalDate.now(ClockConfiguration.BERLIN)

    private suspend fun insertChange(
        eventId: Long,
        field: String,
        from: String,
        to: String,
        daysAgo: Long
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.event_change (event_id, field, old_value, new_value, seen_at) " +
                    "VALUES (:eventId, :field, :from, :to, :seenAt)"
            ).bind("eventId", eventId)
            .bind("field", field)
            .bind("from", from)
            .bind("to", to)
            .bind("seenAt", OffsetDateTime.now().minusDays(daysAgo))
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    @Test
    fun `lists the recent changes newest first, with venue names, and leaves out older ones`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val festsaal = insertVenue("Festsaal Kreuzberg", "festsaal-kreuzberg")
            val event = insertEvent(festsaal, "A Night", "a-night", today.plusDays(3))
            insertChange(event, "START_TIME", "22:00", "23:00", daysAgo = 2)
            insertChange(event, "STATUS", "SCHEDULED", "CANCELLED", daysAgo = 1)
            insertChange(event, "VENUE", "$lido", "$festsaal", daysAgo = 5)
            insertChange(event, "EVENT_DATE", "${today.plusDays(1)}", "${today.plusDays(3)}", daysAgo = 15)

            webTestClient
                .get()
                .uri("/events/a-night")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.changes[*].field")
                .isEqualTo(listOf("STATUS", "START_TIME", "VENUE"))
                .jsonPath("$.changes[1].from")
                .isEqualTo("22:00")
                .jsonPath("$.changes[1].to")
                .isEqualTo("23:00")
                .jsonPath("$.changes[2].from")
                .isEqualTo("Lido")
                .jsonPath("$.changes[2].to")
                .isEqualTo("Festsaal Kreuzberg")
        }

    @Test
    fun `an event that has not moved carries an empty list`(): Unit =
        runBlocking {
            insertEvent(insertVenue("Lido", "lido"), "A Night", "a-night", today.plusDays(3))

            webTestClient
                .get()
                .uri("/events/a-night")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.changes")
                .isEmpty
        }
}
