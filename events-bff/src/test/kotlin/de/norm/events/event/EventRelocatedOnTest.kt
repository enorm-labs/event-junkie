package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/** A relocated event carries the new date its note states, on the list item and the detail (#3613). */
class EventRelocatedOnTest : BaseControllerTest() {
    private val day: LocalDate = LocalDate.now(ClockConfiguration.BERLIN).plusDays(5)
    private val newDate: LocalDate = day.plusMonths(6)

    @Test
    fun `the detail and the list item carry the house and the new date`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            relocate(insertEvent(lido, "GHOSTKID", "moved", day), "Hole 44", newDate)

            webTestClient
                .get()
                .uri("/events/moved")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.relocatedTo")
                .isEqualTo("Hole 44")
                .jsonPath("$.relocatedOn")
                .isEqualTo(newDate.toString())
            webTestClient
                .get()
                .uri("/events?from=$day&to=$day")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].relocatedTo")
                .isEqualTo("Hole 44")
                .jsonPath("$.content[0].relocatedOn")
                .isEqualTo(newDate.toString())
        }

    @Test
    fun `a move that names only the house carries no date`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            relocate(insertEvent(lido, "Kate Ryan", "moved", day), "Hole 44", null)

            webTestClient
                .get()
                .uri("/events/moved")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.relocatedTo")
                .isEqualTo("Hole 44")
                .jsonPath("$.relocatedOn")
                .doesNotExist()
        }

    private suspend fun relocate(
        eventId: Long,
        house: String,
        on: LocalDate?
    ) {
        databaseClient
            .sql("UPDATE events.event SET status = 'RELOCATED', relocated_to = :house, relocated_on = :on WHERE id = :id")
            .bind("house", house)
            .let { if (on == null) it.bindNull("on", LocalDate::class.java) else it.bind("on", on) }
            .bind("id", eventId)
            .await()
    }
}
