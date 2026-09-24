package de.norm.events.artist

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody

class ArtistControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var enrichmentStore: ArtistEnrichmentStore

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
                .expectBody<PageResponse<ArtistResponse>>()
                .returnResult()
                .responseBody!!

        // `.content`, never `.size`: on the envelope that field is the *page* size, so an assertion
        // written against it passes whatever the listing returned (#810).
        artists.content.size shouldBeGreaterThanOrEqual 1
        artists.content.map { it.id } shouldContain created.id
        // Everything created here fits on one page, so the total must equal what came back.
        artists.totalElements shouldBe artists.content.size.toLong()
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

    @Test
    fun `PUT keeps a licensed text's credit and its other-language lead while the text is unchanged, and drops both once a person edits the text`() {
        val created = createArtist(ArtistRequestFixtures.adicts(description = null))
        val lead = "The Adicts are an English punk rock band from Ipswich, formed in 1975 and known for their Clockwork Orange look."
        runBlocking {
            enrichmentStore.store(
                created.id,
                mapOf(
                    "description" to lead,
                    "description_language" to "en",
                    "description_attribution" to "Wikipedia",
                    "description_licence_id" to "CC-BY-SA-4.0",
                    "description_source_url" to "https://en.wikipedia.org/wiki/The_Adicts",
                    "description_alt" to "The Adicts sind eine englische Punkband aus Ipswich, die 1975 gegründet wurde.",
                    "description_alt_language" to "de",
                    "description_alt_attribution" to "Wikipedia",
                    "description_alt_licence_id" to "CC-BY-SA-4.0",
                    "description_alt_source_url" to "https://de.wikipedia.org/wiki/The_Adicts"
                )
            )
        }

        fun put(description: String) =
            webTestClient
                .put()
                .uri("/api/admin/artists/${created.id}")
                .bodyValue(ArtistRequestFixtures.adicts(description = description))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<ArtistResponse>()
                .returnResult()
                .responseBody!!

        val unchanged = put(lead)
        unchanged.descriptionAttribution shouldBe "Wikipedia"
        unchanged.descriptionLanguage shouldBe "en"
        unchanged.descriptionSourceUrl shouldBe "https://en.wikipedia.org/wiki/The_Adicts"
        unchanged.descriptionAltLanguage shouldBe "de"
        unchanged.descriptionAltSourceUrl shouldBe "https://de.wikipedia.org/wiki/The_Adicts"

        val edited = put("Punk aus Ipswich, seit 1975.")
        edited.description shouldBe "Punk aus Ipswich, seit 1975."
        edited.descriptionLanguage shouldBe null
        edited.descriptionAttribution shouldBe null
        edited.descriptionLicenceId shouldBe null
        edited.descriptionSourceUrl shouldBe null
        edited.descriptionAlt shouldBe null
        edited.descriptionAltLanguage shouldBe null
        edited.descriptionAltAttribution shouldBe null
        edited.descriptionAltLicenceId shouldBe null
        edited.descriptionAltSourceUrl shouldBe null

        deleteArtist(created.id)
    }
}
