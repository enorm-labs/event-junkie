package de.norm.events.artist

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody

class ArtistWikidataGenreControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var artistRepository: ArtistRepository

    private suspend fun artist(
        name: String,
        match: String,
        vararg genres: String
    ): Long {
        val id = requireNotNull(artistRepository.save(ArtistEntity(name = name, slug = name.lowercase().replace(' ', '-'))).id)
        databaseClient
            .sql("UPDATE events.artist SET musicbrainz_match = :match, wikidata_genres = :genres WHERE id = :id")
            .bind("match", match)
            .bind("genres", arrayOf(*genres))
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    @Test
    fun `lists the EXACT artists that hold a genre, with the item ids in statement order (#5252)`() {
        val kaiser =
            runBlocking {
                artist("Roland Kaiser", "EXACT", "Q959583", "Q9759").also {
                    artist("No Genre Act", "EXACT")
                    artist("Unchecked Act", "UNCHECKED", "Q11399")
                }
            }

        webTestClient
            .get()
            .uri("/api/admin/artists/wikidata-genres")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<List<ArtistWikidataGenresResponse>>()
            .returnResult()
            .responseBody shouldBe listOf(ArtistWikidataGenresResponse(kaiser, "roland-kaiser", "Roland Kaiser", listOf("Q959583", "Q9759")))
    }
}
