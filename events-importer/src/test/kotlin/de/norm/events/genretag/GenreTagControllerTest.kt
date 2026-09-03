package de.norm.events.genretag

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import de.norm.events.event.EventRequest
import de.norm.events.event.EventRequestFixtures
import de.norm.events.event.EventResponse
import de.norm.events.venue.VenueRequest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

class GenreTagControllerTest : BaseControllerTest() {
    private fun createVenue(request: VenueRequest = VenueRequestFixtures.astra()): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun createEvent(request: EventRequest): EventResponse =
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    @Test
    fun `GET genre-tags returns empty list when no events exist`() {
        val tags =
            webTestClient
                .get()
                .uri("/api/admin/genre-tags")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<GenreTagResponse>>()
                .returnResult()
                .responseBody!!

        tags.content shouldHaveSize 0
        tags.totalElements shouldBe 0L
    }

    @Test
    fun `POST event with genre auto-creates genre tags`() {
        val venue = createVenue()
        val event =
            createEvent(
                EventRequestFixtures.adicts(
                    venueId = venue.id,
                    genre = "Punk"
                )
            )

        event.genreTags shouldContainExactlyInAnyOrder listOf("Punk")

        val tags =
            webTestClient
                .get()
                .uri("/api/admin/genre-tags")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<GenreTagResponse>>()
                .returnResult()
                .responseBody!!

        // `.content`, never `.size`: on the envelope that field is the *page* size (#810).
        tags.content.size shouldBeGreaterThanOrEqual 1
        tags.content.map { it.name } shouldContainExactlyInAnyOrder listOf("Punk")
        tags.totalElements shouldBe 1L
    }

    @Test
    fun `POST event with multi-genre creates multiple tags`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Multi Genre Venue"))
        val event =
            createEvent(
                EventRequestFixtures.create(
                    venueId = venue.id,
                    sourceId = "test:multi-genre",
                    genre = "Indie, Rock, Folk"
                )
            )

        event.genreTags shouldContainExactlyInAnyOrder listOf("Indie", "Rock", "Folk")
    }

    @Test
    fun `POST event without genre has empty genreTags`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "No Genre Venue"))
        val event =
            createEvent(
                EventRequestFixtures.create(
                    venueId = venue.id,
                    sourceId = "test:no-genre",
                    genre = null
                )
            )

        event.genreTags shouldHaveSize 0
    }

    @Test
    fun `genre tag synonyms are normalized`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Synonym Venue"))
        val event =
            createEvent(
                EventRequestFixtures.create(
                    venueId = venue.id,
                    sourceId = "test:synonym-genre",
                    genre = "Hip-Hop, Rap"
                )
            )

        // Both "Hip-Hop" and "Rap" should normalize to "Hip Hop"
        event.genreTags shouldContainExactlyInAnyOrder listOf("Hip Hop")
    }

    @Test
    fun `GET genre-tag by ID returns the tag`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Get Tag Venue"))
        createEvent(
            EventRequestFixtures.create(
                venueId = venue.id,
                sourceId = "test:get-tag",
                genre = "Jazz"
            )
        )

        val tags =
            webTestClient
                .get()
                .uri("/api/admin/genre-tags")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<GenreTagResponse>>()
                .returnResult()
                .responseBody!!

        val jazzTag = tags.content.first { it.name == "Jazz" }

        webTestClient
            .get()
            .uri("/api/admin/genre-tags/${jazzTag.id}")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<GenreTagResponse>()
            .consumeWith { result ->
                val tag = result.responseBody!!
                tag.name shouldBe "Jazz"
                tag.slug shouldBe "jazz"
            }
    }

    @Test
    fun `GET genre-tag by non-existent ID returns 404`() {
        webTestClient
            .get()
            .uri("/api/admin/genre-tags/99999")
            .exchange()
            .expectStatus()
            .isNotFound
    }
}
