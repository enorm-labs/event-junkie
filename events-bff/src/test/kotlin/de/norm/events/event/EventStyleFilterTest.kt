package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** `genre` repeats, and an event tagged with any given style matches; beside `family` it narrows (#3980). */
class EventStyleFilterTest : BaseControllerTest() {
    @Test
    fun `GET events filters by one style, several styles, and ignores an unknown slug among them`(): Unit =
        runBlocking {
            val today = LocalDate.now(BERLIN)
            val venueId = insertVenue("Astra", "astra")
            val techno = insertGenreTag("Techno", "techno", family = "electronic")
            val house = insertGenreTag("House", "house", family = "electronic")
            val ambient = insertGenreTag("Ambient", "ambient", family = "electronic")
            val rap = insertGenreTag("Rap", "rap", family = "hip-hop")
            linkGenre(insertEvent(venueId, "Rave", "rave", today), techno)
            linkGenre(insertEvent(venueId, "Loft", "loft", today.plusDays(1)), house)
            linkGenre(insertEvent(venueId, "Drift", "drift", today.plusDays(2)), ambient)
            linkGenre(insertEvent(venueId, "Cypher", "cypher", today.plusDays(3)), rap)
            // Tagged with both chosen styles, it is still one row.
            val both = insertEvent(venueId, "Both", "both", today.plusDays(4))
            linkGenre(both, techno)
            linkGenre(both, house)

            mapOf(
                "genre=techno" to listOf("rave", "both"),
                "family=electronic&genre=techno" to listOf("rave", "both"),
                "family=electronic&genre=techno&genre=house" to listOf("rave", "loft", "both"),
                "genre=house&genre=techno&genre=house" to listOf("rave", "loft", "both"),
                "genre=techno&genre=unknown" to listOf("rave", "both"),
                "genre=unknown" to emptyList(),
                // A hand-built URL across families is a plain "or" over the slugs.
                "genre=techno&genre=rap" to listOf("rave", "cypher", "both"),
                "family=hip-hop&genre=techno&genre=house" to emptyList()
            ).forEach { (query, slugs) ->
                webTestClient
                    .get()
                    .uri("/events?$query")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.content[*].slug")
                    .isEqualTo(slugs)
            }

            webTestClient
                .get()
                .uri("/events/calendar?from=$today&to=${today.plusDays(7)}&family=electronic&genre=house&genre=techno")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("rave", "loft", "both"))
        }
}
