package de.norm.events.venue

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await

/**
 * A venue we know but do not import (#2766): no `event_source` row points at it. The list and the detail say so, the filter
 * splits on it, and the detail carries the programme link. When an importer lands, the source row flips it with no data change.
 */
class VenueImportedTest : BaseControllerTest() {
    private var hinterhofId = 0L

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            source(insertVenue("Astra", "astra"))
            hinterhofId = insertVenue("Hinterhof Bar", "hinterhof-bar")
            databaseClient
                .sql(
                    "UPDATE events.venue SET programme_url = 'https://hinterhof.example/programm', " +
                        "instagram_url = 'https://www.instagram.com/hinterhof_bar/', facebook_url = 'https://www.facebook.com/hinterhofbar/' " +
                        "WHERE slug = 'hinterhof-bar'"
                ).await()
        }

    @Test
    fun `the list says which venues are imported`() {
        webTestClient
            .get()
            .uri("/venues?sort=name")
            .exchange()
            .expectBody()
            .jsonPath("$.content[0].slug")
            .isEqualTo("astra")
            .jsonPath("$.content[0].imported")
            .isEqualTo(true)
            .jsonPath("$.content[1].slug")
            .isEqualTo("hinterhof-bar")
            .jsonPath("$.content[1].imported")
            .isEqualTo(false)
    }

    @Test
    fun `the filter lists one side or the other, and both without it`() {
        expectSlugs("/venues?imported=true", "astra")
        expectSlugs("/venues?imported=false", "hinterhof-bar")
        expectSlugs("/venues?sort=name", "astra", "hinterhof-bar")
    }

    @Test
    fun `the detail carries the state, the programme link and the social links`() {
        webTestClient
            .get()
            .uri("/venues/hinterhof-bar")
            .exchange()
            .expectBody()
            .jsonPath("$.imported")
            .isEqualTo(false)
            .jsonPath("$.programmeUrl")
            .isEqualTo("https://hinterhof.example/programm")
            .jsonPath("$.instagramUrl")
            .isEqualTo("https://www.instagram.com/hinterhof_bar/")
            .jsonPath("$.facebookUrl")
            .isEqualTo("https://www.facebook.com/hinterhofbar/")

        webTestClient
            .get()
            .uri("/venues/astra")
            .exchange()
            .expectBody()
            .jsonPath("$.imported")
            .isEqualTo(true)
            .jsonPath("$.programmeUrl")
            .doesNotExist()
            .jsonPath("$.instagramUrl")
            .doesNotExist()
            .jsonPath("$.facebookUrl")
            .doesNotExist()
    }

    @Test
    fun `a source added later makes the venue imported`(): Unit =
        runBlocking {
            source(hinterhofId)

            expectSlugs("/venues?imported=false")
            expectSlugs("/venues?imported=true", "astra", "hinterhof-bar")
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

    private suspend fun source(venueId: Long) {
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                    "VALUES (:venueId, 'source', 'source-' || :venueId, 'https://example.org', 'A_TRANE')"
            ).bind("venueId", venueId)
            .await()
    }
}
