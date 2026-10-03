package de.norm.events.event

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** `district` repeats, and an event at a venue in any given district matches (#2422). */
class EventDistrictFilterTest : BaseControllerTest() {
    @Test
    fun `GET events filters by district`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido", district = "kreuzberg")
            val sameiden = insertVenue("SameHeaven", "sameheaven", district = "neukoelln")
            insertEvent(lido, "Kreuzberg Gig", "kreuzberg-gig", LocalDate.now())
            insertEvent(sameiden, "Neukölln Gig", "neukoelln-gig", LocalDate.now())

            webTestClient
                .get()
                .uri("/events?district=kreuzberg")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].slug")
                .isEqualTo("kreuzberg-gig")
                .jsonPath("$.content[0].venue.district")
                .isEqualTo("kreuzberg")
        }

    @Test
    fun `GET events filters by several districts, any of them matching`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido", district = "kreuzberg")
            val sameheaven = insertVenue("SameHeaven", "sameheaven", district = "neukoelln")
            val berghain = insertVenue("Berghain", "berghain", district = "friedrichshain")
            insertEvent(lido, "Kreuzberg Gig", "kreuzberg-gig", LocalDate.now())
            insertEvent(sameheaven, "Neukölln Gig", "neukoelln-gig", LocalDate.now().plusDays(1))
            insertEvent(berghain, "Friedrichshain Gig", "friedrichshain-gig", LocalDate.now().plusDays(2))

            mapOf(
                "district=kreuzberg&district=neukoelln" to listOf("kreuzberg-gig", "neukoelln-gig"),
                "district=neukoelln&district=unknown" to listOf("neukoelln-gig"),
                "district=unknown" to emptyList()
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
                .uri("/events/calendar?from=${LocalDate.now()}&to=${LocalDate.now().plusDays(7)}&district=friedrichshain&district=kreuzberg")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("kreuzberg-gig", "friedrichshain-gig"))
        }
}
