package de.norm.events.artist

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Runs V088 (#2417) against planted rows on a database migrated to just before it: the four fused
 * OHM rows on their nights, `Tafkamp` already holding a row with an enrichment link, and
 * `All night long` billed on two nights.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitOhmB2bArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("86").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            plantArtist(statement, "Makam b2b Tafkamp", "makam-b2b-tafkamp")
            plantArtist(statement, "Tafkamp", "tafkamp")
            statement.execute("UPDATE artist SET bandcamp_url = 'https://tafkamp.bandcamp.com/' WHERE slug = 'tafkamp'")
            plantArtist(statement, "All night long", "all-night-long")
            plantArtist(statement, "The Evil B-Side Twins ( DJ Tool & Yazzus)", "the-evil-b-side-twins-dj-tool-yazzus")
            plantArtist(statement, "Asphalt DJ", "asphalt-dj")
            plantArtist(statement, "Jesse G b2b DJ Heartbreak", "jesse-g-b2b-dj-heartbreak")
            plantArtist(statement, "Jesse G", "jesse-g")
            plantArtist(statement, "Reduks", "reduks")
            plantArtist(statement, "Shuray & Walle b2b Naomi", "shuray-walle-b2b-naomi")
            plantArtist(statement, "Shuray & Walle", "shuray-walle")
            plantArtist(statement, "Amaliah b2b Niks", "amaliah-b2b-niks")
            plantArtist(statement, "DJ Northern", "dj-northern")
            plantEvent(statement, "ohm:2026-10-03-braindance", "makam-b2b-tafkamp" to "DJ", "all-night-long" to "DJ")
            plantEvent(statement, "ohm:2026-09-12-evil-twin-rekords", "the-evil-b-side-twins-dj-tool-yazzus" to "DJ", "all-night-long" to "DJ")
            plantEvent(
                statement,
                "ohm:2026-10-17-nice-2-be-nice",
                "asphalt-dj" to "DJ",
                "jesse-g-b2b-dj-heartbreak" to "DJ",
                "reduks" to "HEADLINER",
                "shuray-walle-b2b-naomi" to "DJ"
            )
            plantEvent(statement, "ohm:2026-10-31-garage-girls", "amaliah-b2b-niks" to "DJ", "dj-northern" to "DJ")
        }
        flyway("88").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `deletes the fused rows and the set note`() {
        val artists = artists()
        (FUSED + "all-night-long").forEach { artists shouldNotContainKey it }
    }

    @Test
    fun `bills Makam and the existing Tafkamp row on BRAINDANCE`() {
        lineupOf("ohm:2026-10-03-braindance") shouldContainExactly listOf(Triple("makam", "DJ", 0), Triple("tafkamp", "DJ", 1))
        query("SELECT bandcamp_url FROM artist WHERE slug = 'tafkamp'") { it.getString(1) }.single() shouldBe "https://tafkamp.bandcamp.com/"
        artists()["makam"] shouldBe "Makam"
    }

    @Test
    fun `keeps the rest of a night the set note was billed on`() {
        lineupOf("ohm:2026-09-12-evil-twin-rekords") shouldContainExactly listOf(Triple("the-evil-b-side-twins-dj-tool-yazzus", "DJ", 0))
    }

    @Test
    fun `splits two back-to-back lines in place and keeps the roles`() {
        lineupOf("ohm:2026-10-17-nice-2-be-nice") shouldContainExactly
            listOf(
                Triple("asphalt-dj", "DJ", 0),
                Triple("jesse-g", "DJ", 1),
                Triple("dj-heartbreak", "DJ", 2),
                Triple("reduks", "HEADLINER", 3),
                Triple("shuray-walle", "DJ", 4),
                Triple("naomi", "DJ", 5)
            )
        lineupOf("ohm:2026-10-31-garage-girls") shouldContainExactly
            listOf(Triple("amaliah", "DJ", 0), Triple("niks", "DJ", 1), Triple("dj-northern", "DJ", 2))
        query("SELECT count(*) FROM artist WHERE slug IN ('jesse-g', 'shuray-walle')") { it.getInt(1) }.single() shouldBe 2
    }

    @Test
    fun `every act's slug is the slug of its name`() {
        val migration = File("src/main/resources/db/migration/V088__split_ohm_b2b_artists.sql").readText()
        val acts = ACT_ROW.findAll(migration).map { it.destructured }.toList()
        acts.size shouldBe 8
        acts
            .filter { (slug, name) -> SlugGenerator.slugify(name.replace("''", "'")) != slug }
            .map { (slug, name) -> "'$slug' is named '$name'" }
            .shouldBeEmpty()
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun plantArtist(
        statement: Statement,
        name: String,
        slug: String
    ) {
        statement.execute("INSERT INTO artist (name, slug) VALUES ('${name.replace("'", "''")}', '$slug')")
    }

    /** One night billing [lineup] as (artist slug, role), in billing order. */
    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg lineup: Pair<String, String>
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', '$sourceId', DATE '2026-11-15', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        lineup.forEachIndexed { order, (slug, role) ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, '$role', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun artists(): Map<String, String> = query("SELECT slug, name FROM artist") { it.getString(1) to it.getString(2) }.toMap()

    private fun lineupOf(sourceId: String): List<Triple<String, String, Int>> =
        query(
            "SELECT a.slug, ea.role, ea.billing_order FROM event_artist ea " +
                "JOIN artist a ON a.id = ea.artist_id JOIN event e ON e.id = ea.event_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { Triple(it.getString(1), it.getString(2), it.getInt(3)) }

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }

    private companion object {
        val FUSED = listOf("makam-b2b-tafkamp", "jesse-g-b2b-dj-heartbreak", "shuray-walle-b2b-naomi", "amaliah-b2b-niks")

        /** One `('fused-slug', 'slug', 'Name', <position>)` row of the act list. */
        val ACT_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'((?:[^']|'')+)',\s*\d+\)""")
    }
}
