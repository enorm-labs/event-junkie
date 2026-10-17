package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/** An event's festival (#3273): the `festival` field on every shape, and the `festival` filter. */
class EventFestivalTest : BaseControllerTest() {
    private val today: LocalDate = LocalDate.now(ClockConfiguration.BERLIN)

    private suspend fun insertFestival(
        slug: String,
        name: String
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.festival (slug, name, url, first_day, last_day, notice_de, notice_en) " +
                    "VALUES (:slug, :name, 'https://festival.example/', :firstDay, :lastDay, 'De.', 'En.') RETURNING id"
            ).bind("slug", slug)
            .bind("name", name)
            .bind("firstDay", today)
            .bind("lastDay", today.plusDays(2))
            .map { row -> requireNotNull(row.get("id", Long::class.javaObjectType)) }
            .one()
            .awaitSingle()

    private suspend fun link(
        eventId: Long,
        festivalId: Long
    ) {
        databaseClient.sql("UPDATE events.event SET festival_id = $festivalId WHERE id = $eventId").await()
    }

    @Test
    fun `every event shape carries its festival, and null without one`(): Unit =
        runBlocking {
            val venue = insertVenue("Festival Hall", "festival-hall")
            val fete = insertFestival("fete-de-la-musique-2027", "Fête de la Musique 2027")
            link(insertEvent(venue, "Open Air", "open-air", today), fete)
            insertEvent(venue, "Plain Gig", "plain-gig", today)

            webTestClient
                .get()
                .uri("/events/open-air")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.festival.slug")
                .isEqualTo("fete-de-la-musique-2027")
                .jsonPath("$.festival.name")
                .isEqualTo("Fête de la Musique 2027")
            webTestClient
                .get()
                .uri("/events/plain-gig")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.festival")
                .doesNotExist()

            listOf("/events", "/events/today", "/events/calendar?from=$today&to=$today").forEach { uri ->
                val root = if (uri == "/events") "$.content" else "$"
                webTestClient
                    .get()
                    .uri(uri)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$root[?(@.slug == 'open-air')].festival.name")
                    .isEqualTo(listOf("Fête de la Musique 2027"))
                    .jsonPath("$root[?(@.slug == 'plain-gig')].festival")
                    .isEqualTo(listOf(null))
            }
        }

    @Test
    fun `the festival filter lists its events, narrows with other filters, and an unknown slug gives an empty page`(): Unit =
        runBlocking {
            val hall = insertVenue("Festival Hall", "festival-hall")
            val garden = insertVenue("Festival Garden", "festival-garden")
            val fete = insertFestival("fete", "Fête")
            val other = insertFestival("other-week", "Other Week")
            link(insertEvent(hall, "Hall Set", "hall-set", today, eventType = "PARTY"), fete)
            link(insertEvent(garden, "Garden Set", "garden-set", today.plusDays(1)), fete)
            link(insertEvent(garden, "Other Set", "other-set", today), other)
            insertEvent(hall, "No Festival", "no-festival", today)

            fun slugsOf(query: String) =
                webTestClient
                    .get()
                    .uri("/events?$query")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()

            slugsOf("festival=fete&sort=eventDate")
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[*].slug")
                .isEqualTo(listOf("hall-set", "garden-set"))
            slugsOf("festival=fete&venue=festival-garden")
                .jsonPath("$.content[*].slug")
                .isEqualTo(listOf("garden-set"))
            slugsOf("festival=fete&eventType=PARTY")
                .jsonPath("$.content[*].slug")
                .isEqualTo(listOf("hall-set"))
            slugsOf("festival=nowhere")
                .jsonPath("$.totalElements")
                .isEqualTo(0)
            webTestClient
                .get()
                .uri("/events/calendar?from=$today&to=$today&festival=other-week")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("other-set"))
        }
}
