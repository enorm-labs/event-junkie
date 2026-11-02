package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.expectBody

/** The admin event read and list return genre tags alphabetically, as the BFF does (#4381). */
class EventGenreTagOrderTest : BaseControllerTest() {
    @Test
    fun `GET event by id and the event list return genre tags alphabetically, ignoring case`() {
        val venue = createVenue()
        val event = createEvent(venue.id)
        // Ids run against the names, and one is lower case, so neither id order nor a byte sort passes.
        runBlocking {
            listOf("Zouk" to "zouk-4381", "Techno" to "techno-4381", "house" to "house-4381", "Ambient" to "ambient-4381")
                .forEach { (name, slug) -> linkGenre(event.id, insertGenreTag(name, slug)) }
        }
        val expected = listOf("Ambient", "house", "Techno", "Zouk")

        val single =
            webTestClient
                .get()
                .uri("/api/admin/events/${event.id}")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<EventResponse>()
                .returnResult()
                .responseBody!!
        single.genreTags shouldBe expected

        val page =
            webTestClient
                .get()
                .uri("/api/admin/events?venueId=${venue.id}")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<EventResponse>>()
                .returnResult()
                .responseBody!!
        page.content.single { it.id == event.id }.genreTags shouldBe expected
    }

    private fun createVenue(): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.create(name = "Genre Order Venue"))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun createEvent(venueId: Long): EventResponse =
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(EventRequestFixtures.create(venueId = venueId, sourceId = "test:genre-order"))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    private suspend fun insertGenreTag(
        name: String,
        slug: String
    ): Long =
        databaseClient
            .sql("INSERT INTO events.genre_tag (name, slug) VALUES (:name, :slug) RETURNING id")
            .bind("name", name)
            .bind("slug", slug)
            .map { row -> row.get(0, Long::class.javaObjectType)!! }
            .one()
            .awaitSingle()

    private suspend fun linkGenre(
        eventId: Long,
        genreTagId: Long
    ) {
        databaseClient
            .sql("INSERT INTO events.event_genre_tag (event_id, genre_tag_id) VALUES (:eventId, :genreTagId)")
            .bind("eventId", eventId)
            .bind("genreTagId", genreTagId)
            .await()
    }
}
