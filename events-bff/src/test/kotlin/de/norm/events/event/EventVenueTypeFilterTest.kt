package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/** `venueType` repeats, and an event at a venue of any given type matches (#361). */
class EventVenueTypeFilterTest : BaseControllerTest() {
    private val today = LocalDate.now(ClockConfiguration.BERLIN)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            insertEvent(venue("Astra", "astra", "{live-venue,club}", district = "friedrichshain"), "Astra Gig", "astra-gig", today)
            insertEvent(venue("Berghain", "berghain", "{club}", district = "friedrichshain"), "Berghain Night", "berghain-night", today.plusDays(1))
            insertEvent(venue("Kneipe", "kneipe", "{bar}", district = "neukoelln"), "Kneipe Quiz", "kneipe-quiz", today.plusDays(2))
            insertEvent(venue("Uncurated", "uncurated", "{}"), "Uncurated Gig", "uncurated-gig", today.plusDays(3))
        }

    @Test
    fun `GET events filters by venue type, any of several`() {
        mapOf(
            "venueType=club" to listOf("astra-gig", "berghain-night"),
            "venueType=bar&venueType=live-venue" to listOf("astra-gig", "kneipe-quiz"),
            "venueType=stadium" to emptyList(),
            "venueType=club&district=neukoelln" to emptyList(),
            "venueType=bar&district=neukoelln" to listOf("kneipe-quiz")
        ).forEach { (query, slugs) ->
            webTestClient
                .get()
                .uri("/events?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(slugs.size)
                .jsonPath("$.content[*].slug")
                .isEqualTo(slugs)
        }
    }

    @Test
    fun `GET events calendar filters by venue type`() {
        webTestClient
            .get()
            .uri("/events/calendar?from=$today&to=${today.plusDays(7)}&venueType=bar&venueType=club")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("astra-gig", "berghain-night", "kneipe-quiz"))
    }

    private suspend fun venue(
        name: String,
        slug: String,
        types: String,
        district: String? = null
    ): Long =
        insertVenue(name, slug, district = district).also {
            databaseClient.sql("UPDATE events.venue SET venue_types = '$types' WHERE id = $it").await()
        }
}
