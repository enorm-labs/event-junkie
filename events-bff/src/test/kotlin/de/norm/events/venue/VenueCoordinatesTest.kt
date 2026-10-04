package de.norm.events.venue

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The two responses the frontend's maps pin venues from, and the coordinate each must carry (#357). */
class VenueCoordinatesTest : BaseControllerTest() {
    private val latitude = BigDecimal("52.507242")
    private val longitude = BigDecimal("13.451803")

    @Test
    fun `GET venues carries each venue's coordinate, and null for a venue without one`(): Unit =
        runBlocking {
            insertVenue("Astra", "astra", latitude = latitude, longitude = longitude)
            insertVenue("Lido", "lido")

            webTestClient
                .get()
                .uri("/venues")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].latitude")
                .isEqualTo(52.507242)
                .jsonPath("$.content[0].longitude")
                .isEqualTo(13.451803)
                .jsonPath("$.content[1].latitude")
                .isEmpty()
        }

    @Test
    fun `GET events calendar carries the venue's coordinate`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra", latitude = latitude, longitude = longitude)
            val today = LocalDate.now(ClockConfiguration.BERLIN)
            insertEvent(venueId, "Tonight", "tonight", today)

            webTestClient
                .get()
                .uri("/events/calendar?from=$today&to=$today")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[0].venue.latitude")
                .isEqualTo(52.507242)
                .jsonPath("$[0].venue.longitude")
                .isEqualTo(13.451803)
        }
}
