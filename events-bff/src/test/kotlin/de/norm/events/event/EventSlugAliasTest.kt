package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/** `GET /events/{slug}` on a slug an event had before a retitle (#5560): the event answers under its own slug. */
class EventSlugAliasTest : BaseControllerTest() {
    private val day: LocalDate = LocalDate.now(BERLIN).plusDays(5)

    @Test
    fun `a former slug answers the event under its own slug`(): Unit =
        runBlocking {
            val venue = insertVenue("Uber Arena", "uber-arena")
            insertAlias("$day-uber-arena-wird", insertEvent(venue, "Konstantinos Argiros", "$day-uber-arena-konstantinos-argiros", day))

            webTestClient
                .get()
                .uri("/events/$day-uber-arena-wird")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("$day-uber-arena-konstantinos-argiros")
                .jsonPath("$.title")
                .isEqualTo("Konstantinos Argiros")
        }

    @Test
    fun `a slug that is both an alias and a live event answers the live event`(): Unit =
        runBlocking {
            val venue = insertVenue("Uber Arena", "uber-arena")
            insertEvent(venue, "Wird", "$day-uber-arena-wird", day, sourceId = "test:live")
            insertAlias("$day-uber-arena-wird", insertEvent(venue, "Konstantinos Argiros", "$day-uber-arena-konstantinos-argiros", day))

            webTestClient
                .get()
                .uri("/events/$day-uber-arena-wird")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("$day-uber-arena-wird")
        }

    @Test
    fun `an unknown slug answers 404`(): Unit =
        runBlocking {
            val venue = insertVenue("Uber Arena", "uber-arena")
            insertAlias("$day-uber-arena-wird", insertEvent(venue, "Konstantinos Argiros", "$day-uber-arena-konstantinos-argiros", day))

            webTestClient
                .get()
                .uri("/events/$day-uber-arena-nobody")
                .exchange()
                .expectStatus()
                .isNotFound
        }

    private suspend fun insertAlias(
        slug: String,
        eventId: Long
    ) = databaseClient
        .sql("INSERT INTO events.event_slug_alias (slug, event_id) VALUES (:slug, :eventId)")
        .bind("slug", slug)
        .bind("eventId", eventId)
        .await()
}
