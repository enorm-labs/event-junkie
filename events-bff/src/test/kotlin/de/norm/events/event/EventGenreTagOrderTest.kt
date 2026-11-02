package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Genre tags in a response are alphabetical, whatever order the rows come back in (#4381). */
class EventGenreTagOrderTest : BaseControllerTest() {
    @Test
    fun `detail and list both return genre tags alphabetically, ignoring case`(): Unit =
        runBlocking {
            val venueId = insertVenue("SO36", "so36")
            val eventId = insertEvent(venueId, "Night", "ordered-night", LocalDate.now(BERLIN))
            // Ids run against the names, and one is lower case, so neither id order nor a byte sort passes.
            listOf("Zouk" to "zouk-4381", "Techno" to "techno-4381", "house" to "house-4381", "Ambient" to "ambient-4381")
                .forEach { (name, slug) -> linkGenre(eventId, insertGenreTag(name, slug)) }

            webTestClient
                .get()
                .uri("/events/ordered-night")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.genreTags")
                .isEqualTo(listOf("Ambient", "house", "Techno", "Zouk"))
            webTestClient
                .get()
                .uri("/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[?(@.slug == 'ordered-night')].genreTags[*]")
                .isEqualTo(listOf("Ambient", "house", "Techno", "Zouk"))
        }
}
