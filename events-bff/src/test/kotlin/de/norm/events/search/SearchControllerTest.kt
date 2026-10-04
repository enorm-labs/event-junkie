package de.norm.events.search

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SearchControllerTest : BaseControllerTest() {
    @Test
    fun `GET search groups the matches of each kind with their totals`(): Unit =
        runBlocking {
            val venue = insertVenue("Berghain", "berghain")
            val other = insertVenue("SO36", "so36")
            insertEvent(venue, "Klubnacht", "klubnacht", LocalDate.now(ClockConfiguration.BERLIN).plusDays(1))
            insertEvent(other, "Berghain Afterhour", "berghain-afterhour", LocalDate.now(ClockConfiguration.BERLIN).plusDays(2))
            insertEvent(other, "Punk Night", "punk-night", LocalDate.now(ClockConfiguration.BERLIN).plusDays(3))
            insertArtist("Berghain Ostgut Ton Allstars", "berghain-ostgut-ton-allstars")
            insertArtist("Dixon", "dixon")
            insertPromoter("Berghain Booking", "berghain-booking")

            webTestClient
                .get()
                .uri("/search?q=berghain")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                // The venue name matches both events at the venue and the one named after it.
                .jsonPath("$.events.total")
                .isEqualTo(2)
                .jsonPath("$.events.items[0].slug")
                .isEqualTo("klubnacht")
                .jsonPath("$.venues.total")
                .isEqualTo(1)
                .jsonPath("$.venues.items[0].slug")
                .isEqualTo("berghain")
                .jsonPath("$.artists.items[0].slug")
                .isEqualTo("berghain-ostgut-ton-allstars")
                .jsonPath("$.promoters.items[0].slug")
                .isEqualTo("berghain-booking")
        }

    @Test
    fun `GET search caps each group at limit and still counts every match`(): Unit =
        runBlocking {
            repeat(3) { insertArtist("Tresor Resident $it", "tresor-resident-$it") }

            webTestClient
                .get()
                .uri("/search?q=tresor&limit=2")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.artists.items.length()")
                .isEqualTo(2)
                .jsonPath("$.artists.total")
                .isEqualTo(3)
                .jsonPath("$.venues.total")
                .isEqualTo(0)
        }

    @Test
    fun `GET search stops counting a kind at 100 and says so, while the list endpoint counts them all`(): Unit =
        runBlocking {
            repeat(101) { insertArtist("Capped Act $it", "capped-act-$it") }
            insertPromoter("Capped Booking", "capped-booking")

            webTestClient
                .get()
                .uri("/search?q=capped")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.artists.total")
                .isEqualTo(100)
                .jsonPath("$.artists.totalCapped")
                .isEqualTo(true)
                .jsonPath("$.promoters.total")
                .isEqualTo(1)
                .jsonPath("$.promoters.totalCapped")
                .isEqualTo(false)

            webTestClient
                .get()
                .uri("/artists?q=capped")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(101)
        }

    @Test
    fun `GET search leaves out past events`(): Unit =
        runBlocking {
            val venue = insertVenue("Lido", "lido")
            insertEvent(venue, "Lido Past", "lido-past", LocalDate.now(ClockConfiguration.BERLIN).minusDays(1))

            webTestClient
                .get()
                .uri("/search?q=lido")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.events.total")
                .isEqualTo(0)
                .jsonPath("$.venues.total")
                .isEqualTo(1)
        }

    @Test
    fun `GET search folds accents and forgives a typo`(): Unit =
        runBlocking {
            insertVenue("ÆDEN", "aeden")
            insertVenue("Berghain", "berghain")

            for ((term, slug) in listOf("aeden" to "aeden", "berghian" to "berghain")) {
                webTestClient
                    .get()
                    .uri("/search?q={q}", term)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.venues.items[0].slug")
                    .isEqualTo(slug)
            }
        }

    @Test
    fun `GET search rejects a short term, a limit out of range and an unknown parameter`(): Unit =
        runBlocking {
            val rejected =
                listOf(
                    emptyMap(),
                    mapOf("q" to ""),
                    // One character once trimmed.
                    mapOf("q" to " a "),
                    mapOf("q" to "ab", "limit" to "0"),
                    mapOf("q" to "ab", "limit" to "21"),
                    mapOf("q" to "ab", "size" to "5")
                )
            for (params in rejected) {
                webTestClient
                    .get()
                    .uri { uri -> params.entries.fold(uri.path("/search")) { builder, (name, value) -> builder.queryParam(name, value) }.build() }
                    .exchange()
                    .expectStatus()
                    .isBadRequest
            }
        }
}
