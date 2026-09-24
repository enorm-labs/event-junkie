package de.norm.events

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventStatus
import kotlinx.coroutines.reactive.awaitSingle
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

            // 30 generated + 12 named upcoming; the past row is the one the default window leaves out.
            get("/events?size=1").jsonPath("$.totalElements").isEqualTo(42)
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

    /**
     * The closed sets of values the site renders differently, each covered by a row. Small enough to
     * demand completeness rather than an allowlist: `EventType` is left out deliberately, because nine
     * values that differ only as a label would make the exception list longer than the enum.
     */
    @Test
    fun `every event status and every line-up role appears in the fixture`(): Unit =
        runBlocking {
            loadFixture()

            assertCovers(
                "SELECT DISTINCT status FROM events.event t WHERE ${OWNED_BY_FIXTURE.getValue("event")}",
                EventStatus.entries.map { it.name }
            )
            assertCovers(
                "SELECT DISTINCT ea.role FROM events.event_artist ea JOIN events.event t ON t.id = ea.event_id " +
                    "WHERE ${OWNED_BY_FIXTURE.getValue("event")}",
                ArtistRole.entries.map { it.name }
            )
        }

    /**
     * Every column of the three main tables is set on at least one row, or waived below with a reason.
     * This is the assertion that keeps the fixture growing with the schema: a migration adding a
     * NULLABLE column loads fine and silently covers nothing, which no other check here would catch.
     */
    @Test
    fun `every column of the main tables is exercised by some row, or waived`(): Unit =
        runBlocking {
            loadFixture()

            val unset =
                databaseClient
                    .sql(
                        TABLES.joinToString(" UNION ALL ") { table ->
                            "SELECT '$table.' || c.column_name AS col FROM information_schema.columns c " +
                                "WHERE c.table_schema = 'events' AND c.table_name = '$table' " +
                                "AND NOT EXISTS (SELECT 1 FROM events.$table t WHERE to_jsonb(t) -> c.column_name <> 'null'::jsonb " +
                                "AND ${OWNED_BY_FIXTURE.getValue(table)})"
                        }
                    ).map { row -> row.get("col", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitSingle()

            assert(unset.toSet() == WAIVED.keys) {
                "Columns no fixture row sets: ${(unset.toSet() - WAIVED.keys).sorted()}. Add a row that " +
                    "exercises each, or waive it in WAIVED with a reason. Waived but now covered, so the " +
                    "waiver is stale: ${(WAIVED.keys - unset.toSet()).sorted()}"
            }
        }

    private suspend fun assertCovers(
        sql: String,
        expected: List<String>
    ) {
        val present =
            databaseClient
                .sql(sql)
                .map { row -> row.get(0, String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
                .toSet()
        assert(present.containsAll(expected)) { "No fixture row carries ${expected - present}; add one per value." }
    }

    private companion object {
        val TABLES = listOf("event", "venue", "artist")

        /**
         * "A row this fixture created", per table. The coverage assertion counts only these, so it
         * says what it means — the dataset covers the column — rather than "something in the database
         * does", which would pass on a row another test left behind and read differently depending on
         * the order the classes ran in.
         */
        val OWNED_BY_FIXTURE =
            mapOf(
                "event" to "(t.source_id LIKE 'fixture-%' OR t.source_id LIKE 'dast-%')",
                "venue" to "t.id IN (SELECT venue_id FROM events.event WHERE source_id LIKE 'fixture-%' OR source_id LIKE 'dast-%')",
                "artist" to
                    "t.id IN (SELECT ea.artist_id FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                    "WHERE e.source_id LIKE 'fixture-%' OR e.source_id LIKE 'dast-%')"
            )

        /**
         * A column no row sets, and why. The value is the reason, so a reader sees the argument and a
         * new entry needs one. Both groups here are decisions the fixture's header states.
         */
        val WAIVED =
            mapOf(
                // No fixture event has an event_source row, so the licence gate reads UNKNOWN_SOURCE and
                // these rows exercise its fail-open path, which is what the site shows for them.
                "event.event_source_id" to "no fixture row has a source; the gate's fail-open path is the one under test",
                "event.image_url" to IMAGES,
                "venue.image_url" to IMAGES,
                "venue.image_attribution" to IMAGES,
                "venue.image_licence_id" to IMAGES,
                "venue.image_source_url" to IMAGES,
                "artist.image_url" to IMAGES,
                "artist.image_attribution" to IMAGES,
                "artist.image_licence_id" to IMAGES,
                "artist.image_source_url" to IMAGES
            )

        // V020 requires the attribution triple beside any image_url, and an image nobody serves would
        // test the wrong thing: the served path is cached_image, which the image module covers.
        const val IMAGES = "no fixture row names an image"
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

    @Test
    fun `the verified ensemble's Wikipedia leads are served with their languages and their credits`(): Unit =
        runBlocking {
            loadFixture()

            get("/artists/mobius-trio")
                .jsonPath("$.description")
                .value<String> { assert(it.startsWith("Møbius Trio ist eine deutsche Jazz-Band")) }
                .jsonPath("$.descriptionLanguage")
                .isEqualTo("de")
                .jsonPath("$.descriptionAttribution")
                .isEqualTo("Wikipedia")
                .jsonPath("$.descriptionLicenceId")
                .isEqualTo("CC-BY-SA-4.0")
                .jsonPath("$.descriptionSourceUrl")
                .isEqualTo("https://de.wikipedia.example/wiki/Møbius_Trio")
                .jsonPath("$.descriptionAlt")
                .value<String> { assert(it.startsWith("Møbius Trio is a German jazz band")) }
                .jsonPath("$.descriptionAltLanguage")
                .isEqualTo("en")
                .jsonPath("$.descriptionAltAttribution")
                .isEqualTo("Wikipedia")
                .jsonPath("$.descriptionAltLicenceId")
                .isEqualTo("CC-BY-SA-4.0")
                .jsonPath("$.descriptionAltSourceUrl")
                .isEqualTo("https://en.wikipedia.example/wiki/Møbius_Trio")
        }
}
