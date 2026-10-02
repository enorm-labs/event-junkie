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
 * Runs V082 (#2365) against planted rows on a database migrated to just before it: the six fused
 * rows on their nights, `Hatebreed` already holding a row with an enrichment link, and the duo
 * `Noah X Petter`, which must not move.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitXJoinedCoBillsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("81").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            plantArtist(statement, "Hatebreed x Life Of Agony", "hatebreed-x-life-of-agony")
            plantArtist(statement, "Hatebreed", "hatebreed")
            statement.execute("UPDATE artist SET instagram_url = 'https://www.instagram.com/hatebreed/' WHERE slug = 'hatebreed'")
            plantArtist(statement, "Tmaro x PapaRaZzle", "tmaro-x-paparazzle")
            plantArtist(statement, "Ifa x Never Back Down", "ifa-x-never-back-down")
            plantArtist(statement, "Marthe X Pilani Bubu", "marthe-x-pilani-bubu")
            plantArtist(statement, "Noah X Petter", "noah-x-petter")
            plantArtist(statement, "Sean Steinfeger", "sean-steinfeger")
            plantArtist(statement, "Slowfoam", "slowfoam")
            plantArtist(statement, "Fhionn x Cathal", "fhionn-x-cathal")
            plantArtist(statement, "Asa Tate", "asa-tate")
            plantArtist(statement, "Process Party x Effetto Notte: Hall of Bats with Lovataraxx", "process-party-x-effetto-notte-hall-of-bats-with-lovataraxx")
            plantArtist(statement, "Hysteric Helen", "hysteric-helen")
            plantArtist(statement, "Olgha", "olgha")
            plantEvent(statement, "columbiahalle", "hatebreed-x-life-of-agony" to "HEADLINER")
            plantEvent(statement, "earlier-hatebreed", "hatebreed" to "HEADLINER")
            plantEvent(statement, "badehaus", "tmaro-x-paparazzle" to "HEADLINER")
            plantEvent(statement, "privatclub", "ifa-x-never-back-down" to "HEADLINER")
            plantEvent(statement, "gretchen-marthe", "marthe-x-pilani-bubu" to "HEADLINER")
            plantEvent(statement, "gretchen-noah", "noah-x-petter" to "HEADLINER", "sean-steinfeger" to "SUPPORT")
            plantEvent(statement, "renate", "slowfoam" to "DJ", "fhionn-x-cathal" to "DJ", "asa-tate" to "DJ")
            plantEvent(
                statement,
                "urban-spree",
                "process-party-x-effetto-notte-hall-of-bats-with-lovataraxx" to "HEADLINER",
                "hysteric-helen" to "HEADLINER",
                "olgha" to "HEADLINER"
            )
        }
        flyway("82").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `deletes the six fused rows and keeps the duo`() {
        val artists = artists()
        FUSED.forEach { artists shouldNotContainKey it }
        artists["noah-x-petter"] shouldBe "Noah X Petter"
        lineupOf("gretchen-noah") shouldContainExactly
            listOf(Triple("noah-x-petter", "HEADLINER", 0), Triple("sean-steinfeger", "SUPPORT", 1))
    }

    @Test
    fun `bills each co-bill's two acts in title order`() {
        lineupOf("badehaus") shouldContainExactly listOf(Triple("tmaro", "HEADLINER", 0), Triple("paparazzle", "HEADLINER", 1))
        lineupOf("privatclub") shouldContainExactly listOf(Triple("ifa", "HEADLINER", 0), Triple("never-back-down", "HEADLINER", 1))
        lineupOf("gretchen-marthe") shouldContainExactly listOf(Triple("marthe", "HEADLINER", 0), Triple("pilani-bubu", "HEADLINER", 1))
        artists()["paparazzle"] shouldBe "PapaRaZzle"
    }

    @Test
    fun `splits the Renate line-up entry in place`() {
        lineupOf("renate") shouldContainExactly
            listOf(
                Triple("slowfoam", "DJ", 0),
                Triple("fhionn", "DJ", 1),
                Triple("cathal", "DJ", 2),
                Triple("asa-tate", "DJ", 3)
            )
    }

    @Test
    fun `reuses the existing Hatebreed row`() {
        lineupOf("columbiahalle") shouldContainExactly
            listOf(Triple("hatebreed", "HEADLINER", 0), Triple("life-of-agony", "HEADLINER", 1))
        query("SELECT count(*) FROM artist WHERE name = 'Hatebreed'") { it.getInt(1) }.single() shouldBe 1
        query("SELECT instagram_url FROM artist WHERE slug = 'hatebreed'") { it.getString(1) }
            .single() shouldBe "https://www.instagram.com/hatebreed/"
        lineupOf("earlier-hatebreed") shouldContainExactly listOf(Triple("hatebreed", "HEADLINER", 0))
    }

    @Test
    fun `bills the Urban Spree night's first act instead of its promoters`() {
        lineupOf("urban-spree") shouldContainExactly
            listOf(
                Triple("lovataraxx", "HEADLINER", 0),
                Triple("hysteric-helen", "HEADLINER", 1),
                Triple("olgha", "HEADLINER", 2)
            )
    }

    @Test
    fun `every act's slug is the slug of its name`() {
        val migration = File("src/main/resources/db/migration/V082__split_x_joined_co_bills.sql").readText()
        val acts = ACT_ROW.findAll(migration).map { it.destructured }.toList()
        acts.size shouldBe 11
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
        val FUSED =
            listOf(
                "hatebreed-x-life-of-agony",
                "tmaro-x-paparazzle",
                "ifa-x-never-back-down",
                "marthe-x-pilani-bubu",
                "fhionn-x-cathal",
                "process-party-x-effetto-notte-hall-of-bats-with-lovataraxx"
            )

        /** One `('fused-slug', 'slug', 'Name', <position>)` row of the act list. */
        val ACT_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'((?:[^']|'')+)',\s*\d+\)""")
    }
}
