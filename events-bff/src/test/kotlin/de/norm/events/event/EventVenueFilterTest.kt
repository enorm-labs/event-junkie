package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** `venue` repeats, and an event at any given venue matches (#3979). */
class EventVenueFilterTest : BaseControllerTest() {
    @Test
    fun `GET events filters by one venue, several venues, and ignores an unknown slug among them`(): Unit =
        runBlocking {
            val today = LocalDate.now(BERLIN)
            val lido = insertVenue("Lido", "lido", district = "kreuzberg")
            val astra = insertVenue("Astra", "astra", district = "friedrichshain")
            val berghain = insertVenue("Berghain", "berghain", district = "friedrichshain")
            insertEvent(lido, "Lido Gig", "lido-gig", today)
            insertEvent(astra, "Astra Gig", "astra-gig", today.plusDays(1))
            insertEvent(berghain, "Berghain Night", "berghain-night", today.plusDays(2))

            mapOf(
                "venue=lido" to listOf("lido-gig"),
                "venue=lido&venue=berghain" to listOf("lido-gig", "berghain-night"),
                "venue=berghain&venue=lido&venue=lido" to listOf("lido-gig", "berghain-night"),
                "venue=astra&venue=unknown" to listOf("astra-gig"),
                "venue=unknown" to emptyList(),
                "venue=lido&venue=astra&district=friedrichshain" to listOf("astra-gig")
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
                .uri("/events/calendar?from=$today&to=${today.plusDays(7)}&venue=astra&venue=lido")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(listOf("lido-gig", "astra-gig"))
        }
}
