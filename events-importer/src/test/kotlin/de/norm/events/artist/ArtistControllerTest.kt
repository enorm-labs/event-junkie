package de.norm.events.artist

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

class ArtistControllerTest : BaseControllerTest() {
    /** Creates an artist via the API and returns the persisted [ArtistResponse]. */
    private fun createArtist(request: ArtistRequest = ArtistRequestFixtures.adicts()): ArtistResponse =
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

    private fun deleteArtist(id: Long) {
        webTestClient
            .delete()
            .uri("/api/admin/artists/$id")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `POST, GET, PUT, DELETE artist lifecycle`() {
        // Create
        val created = createArtist()

        created.name shouldBe "The Adicts"
        created.slug shouldBe "the-adicts"
        created.websiteUrl shouldBe "https://theadicts.net/"

        val id = created.id

        // Read
        webTestClient
            .get()
            .uri("/api/admin/artists/$id")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<ArtistResponse>()
            .consumeWith { result ->
                result.responseBody!!.name shouldBe "The Adicts"
            }

        // Update
        webTestClient
            .put()
            .uri("/api/admin/artists/$id")
            .bodyValue(ArtistRequestFixtures.adicts(name = "The Adicts UK", description = "Updated bio"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<ArtistResponse>()
            .consumeWith { result ->
                val artist = result.responseBody!!
                artist.name shouldBe "The Adicts UK"
                artist.slug shouldBe "the-adicts-uk"
                artist.description shouldBe "Updated bio"
            }

        // Delete
        deleteArtist(id)

        // Verify deleted
        webTestClient
            .get()
            .uri("/api/admin/artists/$id")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET artist by non-existent ID returns 404`() {
        webTestClient
            .get()
            .uri("/api/admin/artists/99999")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET all artists returns list`() {
        val created = createArtist(ArtistRequestFixtures.create(name = "Maid of Ace"))

        val artists =
            webTestClient
                .get()
                .uri("/api/admin/artists")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<List<ArtistResponse>>()
                .returnResult()
                .responseBody!!

        artists.size shouldBeGreaterThanOrEqual 1
        artists.map { it.id } shouldContain created.id
    }

    @Test
    fun `POST artist with blank name returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST artist with whitespace-only name returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.create(name = "   "))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST artist with duplicate name returns 409 with descriptive message`() {
        createArtist(ArtistRequestFixtures.adicts())

        // Second artist with the same name should conflict on slug
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.adicts())
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("An artist with slug 'the-adicts' already exists (generated from name 'The Adicts')")
    }

    @Test
    fun `PUT artist with name that collides with existing slug returns 409`() {
        val first = createArtist(ArtistRequestFixtures.create(name = "Motörhead"))
        val second = createArtist(ArtistRequestFixtures.create(name = "Unique Artist"))

        // Renaming second artist to a name whose slug collides with the first
        webTestClient
            .put()
            .uri("/api/admin/artists/${second.id}")
            .bodyValue(ArtistRequestFixtures.create(name = "Motorhead"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("An artist with slug 'motorhead' already exists (generated from name 'Motorhead')")

        // Clean up
        deleteArtist(first.id)
        deleteArtist(second.id)
    }

    @Test
    fun `PUT artist with blank name returns 400`() {
        val created = createArtist()

        webTestClient
            .put()
            .uri("/api/admin/artists/${created.id}")
            .bodyValue(ArtistRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
