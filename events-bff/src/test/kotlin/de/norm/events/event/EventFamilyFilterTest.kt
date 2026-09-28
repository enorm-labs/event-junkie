package de.norm.events.event

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** `family` repeats, and an event tagged in any given family matches (#1996). */
class EventFamilyFilterTest : BaseControllerTest() {
    @Test
    fun `GET events filters by several genre families, any of them matching`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val techno = insertGenreTag("Techno", "techno", family = "electronic")
            val rap = insertGenreTag("Rap", "rap", family = "hip-hop")
            val metal = insertGenreTag("Metal", "metal", family = "metal")
            linkGenre(insertEvent(venueId, "Rave", "rave", LocalDate.now()), techno)
            linkGenre(insertEvent(venueId, "Cypher", "cypher", LocalDate.now().plusDays(1)), rap)
            linkGenre(insertEvent(venueId, "Mosh", "mosh", LocalDate.now().plusDays(2)), metal)
            // Tagged in both families, it is still one row.
            val crossover = insertEvent(venueId, "Crossover", "crossover", LocalDate.now().plusDays(3))
            linkGenre(crossover, techno)
            linkGenre(crossover, rap)

            mapOf(
                "family=electronic" to listOf("rave", "crossover"),
                "family=electronic&family=hip-hop" to listOf("rave", "cypher", "crossover"),
                "family=hip-hop&family=unknown" to listOf("cypher", "crossover"),
                "family=electronic&family=hip-hop&genre=rap" to listOf("cypher", "crossover"),
                "family=unknown" to emptyList()
            ).forEach { (query, slugs) ->
                searchSlugs("/events?$query") shouldContainExactlyInAnyOrder slugs
            }
        }

    @Test
    fun `GET events calendar filters by several genre families`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val techno = insertGenreTag("Techno", "techno", family = "electronic")
            val rap = insertGenreTag("Rap", "rap", family = "hip-hop")
            val metal = insertGenreTag("Metal", "metal", family = "metal")
            linkGenre(insertEvent(venueId, "Rave", "rave", LocalDate.now().plusDays(1)), techno)
            linkGenre(insertEvent(venueId, "Cypher", "cypher", LocalDate.now().plusDays(2)), rap)
            linkGenre(insertEvent(venueId, "Mosh", "mosh", LocalDate.now().plusDays(3)), metal)

            webTestClient
                .get()
                .uri("/events/calendar?from=${LocalDate.now()}&to=${LocalDate.now().plusDays(7)}&family=metal&family=electronic")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].slug")
                .isEqualTo("rave")
                .jsonPath("$[1].slug")
                .isEqualTo("mosh")
        }

    private fun searchSlugs(uri: String): List<Any?> {
        val body =
            webTestClient
                .get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody

        @Suppress("UNCHECKED_CAST")
        val content = body?.get("content") as List<Map<String, Any?>>
        return content.map { it["slug"] }
    }
}
