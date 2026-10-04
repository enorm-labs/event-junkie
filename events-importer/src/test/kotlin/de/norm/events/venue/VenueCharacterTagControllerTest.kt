package de.norm.events.venue

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Readable
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

class VenueCharacterTagControllerTest : BaseControllerTest() {
    private fun createVenue(): Long =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.astra())
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!
            .id

    private fun put(
        venueId: Long,
        tag: String,
        sourceUrl: String
    ) = webTestClient
        .put()
        .uri("/api/admin/venues/$venueId/character-tags/$tag")
        .bodyValue(VenueCharacterTagRequest(sourceUrl))
        .exchange()

    private fun list(venueId: Long): List<VenueCharacterTagResponse> =
        webTestClient
            .get()
            .uri("/api/admin/venues/$venueId/character-tags")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<List<VenueCharacterTagResponse>>()
            .returnResult()
            .responseBody!!

    @Test
    fun `PUT sets a tag, a second PUT replaces its source, DELETE removes it`() {
        val id = createVenue()

        put(id, "awareness-team", "https://example.com/awareness").expectStatus().isOk
        put(id, "queer", "https://example.com/about")
            .expectStatus()
            .isOk
            .expectBody<VenueCharacterTagResponse>()
            .returnResult()
            .responseBody shouldBe VenueCharacterTagResponse("queer", "https://example.com/about")
        put(id, "queer", "https://example.com/about-us").expectStatus().isOk

        // Vocabulary order, not insertion order.
        list(id) shouldBe
            listOf(
                VenueCharacterTagResponse("queer", "https://example.com/about-us"),
                VenueCharacterTagResponse("awareness-team", "https://example.com/awareness")
            )

        webTestClient
            .delete()
            .uri("/api/admin/venues/$id/character-tags/queer")
            .exchange()
            .expectStatus()
            .isNoContent
        list(id) shouldBe listOf(VenueCharacterTagResponse("awareness-team", "https://example.com/awareness"))

        // Removing a tag that is not set is not an error.
        webTestClient
            .delete()
            .uri("/api/admin/venues/$id/character-tags/queer")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `an unknown tag is a 400 that names the vocabulary`() {
        val id = createVenue()
        put(id, "open-air", "https://example.com/about")
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo(
                "Unknown character tag 'open-air'. Accepted: " +
                    VenueCharacterTag.entries.joinToString(", ") { it.slug } + "."
            )
    }

    @Test
    fun `a tag without an http source URL is a 400`() {
        val id = createVenue()
        put(id, "queer", "").expectStatus().isBadRequest
        put(id, "queer", "so36.com/about").expectStatus().isBadRequest
        list(id) shouldBe emptyList()
    }

    @Test
    fun `an unknown venue is a 404`() {
        put(99999, "queer", "https://example.com/about").expectStatus().isNotFound
        webTestClient
            .get()
            .uri("/api/admin/venues/99999/character-tags")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `deleting the venue deletes its tags`() {
        val id = createVenue()
        put(id, "queer", "https://example.com/about").expectStatus().isOk
        webTestClient
            .delete()
            .uri("/api/admin/venues/$id")
            .exchange()
            .expectStatus()
            .isNoContent
        // Raw count, because the venue and so the endpoint are gone.
        val rows =
            databaseClient
                .sql("SELECT count(*) FROM events.venue_character_tag")
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: -1L }
                .one()
                .block()
        rows shouldBe 0L
    }
}
