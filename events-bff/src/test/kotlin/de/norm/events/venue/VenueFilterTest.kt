package de.norm.events.venue

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await

/**
 * The venue list's type, family, event-type (#327) and character-tag (#2379) filters: any value within one, every one
 * across them.
 */
class VenueFilterTest : BaseControllerTest() {
    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            venue("Astra", "astra", types = "{live-venue,club}", families = "{rock,punk}", eventTypes = "{CONCERT,PARTY}", capacity = 1500)
            venue("Berghain", "berghain", types = "{club}", families = "{electronic}", eventTypes = "{PARTY}")
            venue("Kneipe", "kneipe", types = "{bar}", families = "{}", eventTypes = "{QUIZ}")
            tag("astra", "queer", "https://astra.example/about")
            tag("astra", "awareness-team", "https://astra.example/awareness")
            tag("berghain", "awareness-team", "https://berghain.example/info")
        }

    @Test
    fun `filters by character tag, any of several`(): Unit =
        runBlocking {
            expectSlugs("/venues?character=queer", "astra")
            expectSlugs("/venues?character=queer&character=awareness-team", "astra", "berghain")
            expectSlugs("/venues?character=awareness-team&type=bar")
            expectSlugs("/venues?character=open-air")
        }

    @Test
    fun `the list carries tag slugs and the detail carries each tag's source`(): Unit =
        runBlocking {
            webTestClient
                .get()
                .uri("/venues?q=astra")
                .exchange()
                .expectBody()
                .jsonPath("$.content[0].characterTags")
                .isEqualTo(listOf("awareness-team", "queer"))

            webTestClient
                .get()
                .uri("/venues/astra")
                .exchange()
                .expectBody()
                .jsonPath("$.characterTags[1].tag")
                .isEqualTo("queer")
                .jsonPath("$.characterTags[1].sourceUrl")
                .isEqualTo("https://astra.example/about")

            webTestClient
                .get()
                .uri("/venues/kneipe")
                .exchange()
                .expectBody()
                .jsonPath("$.characterTags.length()")
                .isEqualTo(0)
        }

    @Test
    fun `filters by venue type, any of several`(): Unit =
        runBlocking {
            expectSlugs("/venues?type=club", "astra", "berghain")
            expectSlugs("/venues?type=bar&type=live-venue", "astra", "kneipe")
        }

    @Test
    fun `filters by genre family`(): Unit = runBlocking { expectSlugs("/venues?family=punk", "astra") }

    @Test
    fun `filters by event type, case-insensitive`(): Unit = runBlocking { expectSlugs("/venues?eventType=party", "astra", "berghain") }

    @Test
    fun `combines filters with AND across them`(): Unit = runBlocking { expectSlugs("/venues?type=club&family=electronic", "berghain") }

    @Test
    fun `an unknown value matches nothing`(): Unit = runBlocking { expectSlugs("/venues?type=stadium") }

    @Test
    fun `the list and the detail carry the curated and derived fields`(): Unit =
        runBlocking {
            webTestClient
                .get()
                .uri("/venues?q=astra")
                .exchange()
                .expectBody()
                .jsonPath("$.content[0].venueTypes[1]")
                .isEqualTo("club")
                .jsonPath("$.content[0].capacity")
                .isEqualTo(1500)
                .jsonPath("$.content[0].programmeFamilies[0]")
                .isEqualTo("rock")
                .jsonPath("$.content[0].programmeEventTypes[1]")
                .isEqualTo("PARTY")

            webTestClient
                .get()
                .uri("/venues/kneipe")
                .exchange()
                .expectBody()
                .jsonPath("$.venueTypes[0]")
                .isEqualTo("bar")
                .jsonPath("$.programmeFamilies.length()")
                .isEqualTo(0)
                .jsonPath("$.capacity")
                .doesNotExist()
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

    private suspend fun tag(
        slug: String,
        tag: String,
        sourceUrl: String
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.venue_character_tag (venue_id, tag, source_url) " +
                    "SELECT id, '$tag', '$sourceUrl' FROM events.venue WHERE slug = '$slug'"
            ).await()
    }

    private suspend fun venue(
        name: String,
        slug: String,
        types: String,
        families: String,
        eventTypes: String,
        capacity: Int? = null
    ) {
        insertVenue(name, slug)
        databaseClient
            .sql(
                "UPDATE events.venue SET venue_types = '$types', programme_families = '$families', " +
                    "programme_event_types = '$eventTypes', capacity = ${capacity ?: "NULL"} WHERE slug = '$slug'"
            ).await()
    }
}
