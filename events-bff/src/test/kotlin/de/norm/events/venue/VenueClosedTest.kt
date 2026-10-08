package de.norm.events.venue

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/**
 * A venue that closed for good keeps its row and its page (ADR-046). `closed_on` is its last day open, so the lists
 * keep it through that day and leave it out from the day after. `closed=true` lists the closed ones instead.
 */
class VenueClosedTest : BaseControllerTest() {
    private val today = LocalDate.now(ClockConfiguration.BERLIN)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            insertVenue("Astra", "astra")
            insertVenue("Bergwerk", "bergwerk")
            insertVenue("Closing Club", "closing-club")
            insertVenue("Last Night Bar", "last-night-bar")
            closeOn("bergwerk", today.minusDays(1))
            closeOn("closing-club", today.plusDays(23))
            closeOn("last-night-bar", today)
        }

    @Test
    fun `the list leaves a venue out from the day after its last night`() {
        expectSlugs("/venues?sort=name", "astra", "closing-club", "last-night-bar")
        expectSlugs("/venues?sort=name&closed=false", "astra", "closing-club", "last-night-bar")
    }

    @Test
    fun `closed=true lists only the closed venues`() {
        expectSlugs("/venues?closed=true", "bergwerk")
    }

    @Test
    fun `the feature counts leave a closed venue out too`(): Unit =
        runBlocking {
            databaseClient
                .sql("INSERT INTO events.venue_character_tag (venue_id, tag, source_url) SELECT id, 'queer', 'https://example.org' FROM events.venue")
                .await()

            webTestClient
                .get()
                .uri("/venues/feature-counts")
                .exchange()
                .expectBody()
                .jsonPath("$.queer")
                .isEqualTo(3)
        }

    @Test
    fun `the page of a closed venue stays and carries its last day`() {
        webTestClient
            .get()
            .uri("/venues/bergwerk")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.closedOn")
            .isEqualTo(today.minusDays(1).toString())

        webTestClient
            .get()
            .uri("/venues/astra")
            .exchange()
            .expectBody()
            .jsonPath("$.closedOn")
            .doesNotExist()
    }

    private suspend fun closeOn(
        slug: String,
        day: LocalDate
    ) {
        databaseClient
            .sql("UPDATE events.venue SET closed_on = :day WHERE slug = :slug")
            .bind("day", day)
            .bind("slug", slug)
            .await()
    }

    private fun expectSlugs(
        uri: String,
        vararg slugs: String
    ) {
        webTestClient
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(slugs.size)
            .jsonPath("$.content[*].slug")
            .isEqualTo(slugs.toList())
    }
}
