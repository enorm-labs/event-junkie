package de.norm.events

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import java.io.File
import java.time.LocalDate

/**
 * Loads `fixtures/events.sql` (#272) and reads every shape it promises back through the API. This
 * is what keeps the dataset honest: a migration that adds a NOT NULL column or a CHECK the file does
 * not satisfy fails here, naming the column, and the fix is the file. The other consumers,
 * `dev-env.sh seed-fixture` and the DAST scan, would only find out later or not at all.
 */
class FixtureTest : BaseControllerTest() {
    private val today: LocalDate = LocalDate.now()

    private suspend fun loadFixture() {
        val sql = File(System.getProperty("fixture.sql")).readText()
        databaseClient.sql(sql).await()
    }

    private fun slug(
        shape: String,
        offsetDays: Long
    ): String = "fixture-$shape-${today.plusDays(offsetDays)}"

    private fun get(uri: String): WebTestClient.BodyContentSpec =
        webTestClient
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    @Test
    fun `the fixture loads against the current schema and the default list holds every upcoming row`(): Unit =
        runBlocking {
            loadFixture()

            // 30 generated + 11 named upcoming; the past row is the one the default window leaves out.
            get("/events?size=1").jsonPath("$.totalElements").isEqualTo(41)
            get("/events?size=100").jsonPath("$.content[?(@.slug == '${slug("past", -3)}')]").doesNotExist()
            webTestClient
                .get()
                .uri("/events/${slug("past", -3)}")
                .exchange()
                .expectStatus()
                .isOk
        }

    @Test
    fun `the multi-artist bill lists four roles in billing order`(): Unit =
        runBlocking {
            loadFixture()

            get("/events/${slug("multi-bill", 3)}")
                .jsonPath("$.lineup.length()")
                .isEqualTo(4)
                .jsonPath("$.lineup[0].artist.slug")
                .isEqualTo("mobius-trio")
                .jsonPath("$.lineup[0].role")
                .isEqualTo("HEADLINER")
                .jsonPath("$.lineup[1].role")
                .isEqualTo("SUPPORT")
                .jsonPath("$.lineup[2].role")
                .isEqualTo("SUPPORT")
                .jsonPath("$.lineup[3].artist.slug")
                .isEqualTo("dj-nachtfalter")
                .jsonPath("$.lineup[3].role")
                .isEqualTo("DJ")
                .jsonPath("$.lineup[3].billingOrder")
                .isEqualTo(3)
        }

    @Test
    fun `the festival spans three days on two stages with a promoter`(): Unit =
        runBlocking {
            loadFixture()

            get("/events/${slug("festival", 10)}")
                .jsonPath("$.eventType")
                .isEqualTo("FESTIVAL")
                .jsonPath("$.endDate")
                .isEqualTo(today.plusDays(12).toString())
                .jsonPath("$.endTime")
                .isEqualTo("23:00:00")
                .jsonPath("$.lineup[?(@.stage == 'Hauptbühne')]")
                .value<List<*>> { assert(it.size == 2) }
                .jsonPath("$.lineup[?(@.stage == 'Garten')]")
                .value<List<*>> { assert(it.size == 1) }
                .jsonPath("$.promoters[0].slug")
                .isEqualTo("sommerlaune-festival")
            // On the calendar it is present on its middle day, which is not its event_date.
            val middle = today.plusDays(11)
            get("/events/calendar?from=$middle&to=$middle")
                .jsonPath("$[?(@.slug == '${slug("festival", 10)}')]")
                .exists()
        }

    @Test
    fun `sold out, free and no-price rows land on the right side of the price filters`(): Unit =
        runBlocking {
            loadFixture()

            get("/events?free=true&size=50")
                .jsonPath("$.content[?(@.slug == '${slug("free", 5)}')]")
                .exists()
                .jsonPath("$.content[?(@.slug == '${slug("no-price", 6)}')]")
                .doesNotExist()
            get("/events?excludeSoldOut=true&size=50")
                .jsonPath("$.content[?(@.slug == '${slug("sold-out", 4)}')]")
                .doesNotExist()
            get("/events/${slug("sold-out", 4)}").jsonPath("$.soldOut").isEqualTo(true)
            get("/events/${slug("no-price", 6)}")
                .jsonPath("$.free")
                .isEqualTo(false)
                .jsonPath("$.pricePresale")
                .doesNotExist()
                .jsonPath("$.priceNote")
                .isEqualTo("Spende 5–10 €")
        }

    @Test
    fun `the no-genre row carries no tag and no genre filter returns it`(): Unit =
        runBlocking {
            loadFixture()

            get("/events/${slug("no-genre", 7)}")
                .jsonPath("$.genre")
                .doesNotExist()
                .jsonPath("$.genreTags.length()")
                .isEqualTo(0)
            for (genre in listOf("jazz", "punk", "techno")) {
                get("/events?genre=$genre&size=50")
                    .jsonPath("$.content[?(@.slug == '${slug("no-genre", 7)}')]")
                    .doesNotExist()
            }
        }

    @Test
    fun `two rooms of one venue on one night are two rows under that venue and that day`(): Unit =
        runBlocking {
            loadFixture()

            val night = today.plusDays(15)
            get("/events?venue=kesselhaus-nord&from=$night&to=$night&size=50")
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[*].venue.slug")
                .value<List<String>> { assert(it.toSet() == setOf("kesselhaus-nord")) }
                .jsonPath("$.content[*].subtitle")
                .value<List<String>> { assert(it.toSet() == setOf("Floor 1", "Garten")) }
        }

    @Test
    fun `relocated and cancelled rows stay listed and say so`(): Unit =
        runBlocking {
            loadFixture()

            get("/events/${slug("relocated", 9)}")
                .jsonPath("$.status")
                .isEqualTo("RELOCATED")
                .jsonPath("$.relocatedTo")
                .isEqualTo("Kesselhaus Nord")
            get("/events/${slug("cancelled", 11)}").jsonPath("$.status").isEqualTo("CANCELLED")
            get("/events?size=100")
                .jsonPath("$.content[?(@.slug == '${slug("relocated", 9)}')]")
                .exists()
                .jsonPath("$.content[?(@.slug == '${slug("cancelled", 11)}')]")
                .exists()
        }

    @Test
    fun `the translated description is served with its origin, and the verified artist with its MBID`(): Unit =
        runBlocking {
            loadFixture()

            get("/events/${slug("translated", 13)}")
                .jsonPath("$.descriptionLanguage")
                .isEqualTo("de")
                .jsonPath("$.descriptionAltLanguage")
                .isEqualTo("en")
                .jsonPath("$.descriptionAltOrigin")
                .isEqualTo("MACHINE")
            get("/artists/mobius-trio")
                .jsonPath("$.musicbrainzMatch")
                .isEqualTo("EXACT")
                .jsonPath("$.musicbrainzId")
                .isEqualTo("00000000-0000-4000-8000-000000000272")
        }
}
