package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The origin of a derived genre tag on the event detail (ADR-047). */
class EventGenreOriginTest : BaseControllerTest() {
    @Test
    fun `GET event by slug names the origin of each derived genre tag and none of a source tag`(): Unit =
        runBlocking {
            val venueId = insertVenue("SO36", "so36")
            val eventId = insertEvent(venueId, "Night", "derived-night", LocalDate.now(ClockConfiguration.BERLIN))
            linkGenre(eventId, insertGenreTag("Punk", "punk"), origin = "ARTIST")
            linkGenre(eventId, insertGenreTag("Ska", "ska"), origin = "DESCRIPTION")
            val sourceEventId = insertEvent(venueId, "Other", "source-night", LocalDate.now(ClockConfiguration.BERLIN))
            linkGenre(sourceEventId, insertGenreTag("Jazz", "jazz"))

            webTestClient
                .get()
                .uri("/events/derived-night")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.genreTags.length()")
                .isEqualTo(2)
                .jsonPath("$.derivedGenreTags.Punk")
                .isEqualTo("ARTIST")
                .jsonPath("$.derivedGenreTags.Ska")
                .isEqualTo("DESCRIPTION")
            webTestClient
                .get()
                .uri("/events/source-night")
                .exchange()
                .expectBody()
                .jsonPath("$.genreTags[0]")
                .isEqualTo("Jazz")
                .jsonPath("$.derivedGenreTags.length()")
                .isEqualTo(0)
        }
}
