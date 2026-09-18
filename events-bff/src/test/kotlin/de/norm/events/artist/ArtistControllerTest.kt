package de.norm.events.artist

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.awaitRowsUpdated

class ArtistControllerTest : BaseControllerTest() {
    @Test
    fun `GET artists lists artists with pagination metadata`(): Unit =
        runBlocking {
            insertArtist("The Adicts", "the-adicts")
            insertArtist("Maid Of Ace", "maid-of-ace")

            webTestClient
                .get()
                .uri("/artists")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                // default sort by name asc
                .jsonPath("$.content[0].slug")
                .isEqualTo("maid-of-ace")
        }

    @Test
    fun `GET artists filters by case-insensitive name query`(): Unit =
        runBlocking {
            insertArtist("The Adicts", "the-adicts")
            insertArtist("Maid Of Ace", "maid-of-ace")

            webTestClient
                .get()
                .uri("/artists?q=adicts")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("the-adicts")
        }

    @Test
    fun `GET artists ignores an unknown or malicious sort parameter`(): Unit =
        runBlocking {
            insertArtist("The Adicts", "the-adicts")
            insertArtist("Maid Of Ace", "maid-of-ace")

            // Swagger UI's array placeholder `["string"]` and an injection attempt both contain
            // characters Spring Data rejects; without the sort whitelist these would surface as a
            // 500. They are dropped, so the request succeeds with the default name-ascending order.
            for (sort in listOf("""["string"]""", "name; DROP TABLE events.artist;--")) {
                webTestClient
                    .get()
                    .uri("/artists?sort={sort}", sort)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.totalElements")
                    .isEqualTo(2)
                    .jsonPath("$.content[0].slug")
                    .isEqualTo("maid-of-ace")
            }
        }

    @Test
    fun `GET artists honours a whitelisted sort property`(): Unit =
        runBlocking {
            insertArtist("The Adicts", "the-adicts")
            insertArtist("Maid Of Ace", "maid-of-ace")

            webTestClient
                .get()
                .uri("/artists?sort=name,desc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].slug")
                .isEqualTo("the-adicts")
        }

    @Test
    fun `GET artist by slug returns detail`(): Unit =
        runBlocking {
            insertArtist("The Adicts", "the-adicts", description = "Punk band from Ipswich")

            webTestClient
                .get()
                .uri("/artists/the-adicts")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("the-adicts")
                .jsonPath("$.name")
                .isEqualTo("The Adicts")
                .jsonPath("$.description")
                .isEqualTo("Punk band from Ipswich")
                .jsonPath("$.musicbrainzMatch")
                .isEqualTo("UNCHECKED")
                .jsonPath("$.musicbrainzId")
                .doesNotExist()
        }

    @Test
    fun `GET artist by slug carries the MusicBrainz verdict once the importer stored one`(): Unit =
        runBlocking {
            val id = insertArtist("Accept", "accept")
            databaseClient
                .sql("UPDATE events.artist SET musicbrainz_match = 'EXACT', musicbrainz_id = :mbid, musicbrainz_checked_at = now() WHERE id = :id")
                .bind("mbid", "41f4d85a-0bd7-4602-a3e3-8c47f36efb0a")
                .bind("id", id)
                .fetch()
                .awaitRowsUpdated()

            webTestClient
                .get()
                .uri("/artists/accept")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.musicbrainzMatch")
                .isEqualTo("EXACT")
                .jsonPath("$.musicbrainzId")
                .isEqualTo("41f4d85a-0bd7-4602-a3e3-8c47f36efb0a")
                .jsonPath("$.musicbrainzCheckedAt")
                .exists()
        }

    @Test
    fun `GET artist by unknown slug returns 404`() {
        webTestClient
            .get()
            .uri("/artists/nope")
            .exchange()
            .expectStatus()
            .isNotFound
    }
}
