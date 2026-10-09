package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/**
 * `feature` repeats, and an event needs every given party feature (#2631). The venue's character
 * tags never stand in for a night that states nothing.
 */
class EventFeatureFilterTest : BaseControllerTest() {
    private val today = LocalDate.now(ClockConfiguration.BERLIN)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue("Club", "club")
            event(venueId, "queer-open-end", today, "queer", "open-end")
            event(venueId, "queer-only", today.plusDays(1), "queer")
            event(venueId, "flinta-day-party", today.plusDays(2), "flinta-only", "day-party")
            event(venueId, "techno-night", today.plusDays(3))
            // The venue says it is queer; its nights say nothing, so none of them is a queer night by that.
            databaseClient
                .sql("INSERT INTO events.venue_character_tag (venue_id, tag, source_url) VALUES (:id, 'queer', 'https://club.example/about')")
                .bind("id", venueId)
                .await()
        }

    @Test
    fun `GET events filters by party feature and needs every given one`() {
        mapOf(
            "feature=queer" to listOf("queer-open-end", "queer-only"),
            "feature=QUEER" to listOf("queer-open-end", "queer-only"),
            "feature=queer&feature=open-end" to listOf("queer-open-end"),
            "feature=queer&feature=queer" to listOf("queer-open-end", "queer-only"),
            "feature=queer&feature=day-party" to emptyList(),
            "feature=no-such-feature" to emptyList(),
            "eventType=PARTY" to listOf("queer-open-end", "queer-only", "flinta-day-party", "techno-night")
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

    /** On now is the list with `running=true` over today. */
    @Test
    fun `GET events running today filters by party feature`() {
        webTestClient
            .get()
            .uri("/events?from=$today&to=$today&running=true&feature=open-end")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content[*].slug")
            .isEqualTo(listOf("queer-open-end"))
    }

    @Test
    fun `GET events calendar filters by party feature`() {
        webTestClient
            .get()
            .uri("/events/calendar?from=$today&to=${today.plusDays(7)}&feature=flinta-only")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("flinta-day-party"))
    }

    @Test
    fun `GET event detail lists the features by slug, and none for a night that states none`() {
        webTestClient
            .get()
            .uri("/events/queer-open-end")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.features")
            .isEqualTo(listOf("open-end", "queer"))
        webTestClient
            .get()
            .uri("/events/techno-night")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.features")
            .isEmpty
    }

    private suspend fun event(
        venueId: Long,
        slug: String,
        date: LocalDate,
        vararg features: String
    ) {
        val id = insertEvent(venueId, slug, slug, date, eventType = "PARTY")
        features.forEach { feature ->
            databaseClient
                .sql("INSERT INTO events.event_feature (event_id, feature, matched_phrase) VALUES (:id, :feature, :phrase)")
                .bind("id", id)
                .bind("feature", feature)
                .bind("phrase", "$feature phrase")
                .await()
        }
    }
}
