package de.norm.events.artist

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
 * Runs V080 (#2330) against planted rows on a database migrated to just before it: the four junk
 * rows on their past nights, `Vero` already holding a row with an enrichment link, and a real band
 * the issue also listed, which must not move.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitSeptemberJunkArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("78").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            plantArtist(statement, "(Th)ink About That", "th-ink-about-that")
            plantArtist(statement, "Hum w/ Kyle Hall b2b K15", "hum-w-kyle-hall-b2b-k15")
            plantArtist(statement, "Mamalia’The first lady of modern funk’ft.Mauricio Fleury", "mamaliathe-first-lady-of-modern-funkft-mauricio-fleury")
            plantArtist(statement, "Bulma Brief", "bulma-brief")
            plantArtist(statement, "Moonbootica", "moonbootica")
            plantArtist(statement, "Gloria Game Boyz FEAT. Vero", "gloria-game-boyz-feat-vero")
            plantArtist(statement, "Kristin", "kristin")
            plantArtist(statement, "Vero", "vero")
            statement.execute("UPDATE artist SET instagram_url = 'https://www.instagram.com/veroonebln/' WHERE slug = 'vero'")
            plantArtist(statement, "The Groovy Cellar", "the-groovy-cellar")
            plantEvent(statement, "so36", "th-ink-about-that" to "HEADLINER")
            plantEvent(
                statement,
                "lark",
                "hum-w-kyle-hall-b2b-k15" to "HEADLINER",
                "mamaliathe-first-lady-of-modern-funkft-mauricio-fleury" to "HEADLINER",
                "bulma-brief" to "HEADLINER"
            )
            plantEvent(statement, "butzke", "moonbootica" to "DJ", "gloria-game-boyz-feat-vero" to "DJ", "kristin" to "DJ")
            plantEvent(statement, "aeden", "vero" to "DJ")
            plantEvent(statement, "schokoladen", "the-groovy-cellar" to "HEADLINER")
        }
        flyway("80").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `deletes the four junk rows and keeps the band`() {
        val artists = artists()
        FUSED.forEach { artists shouldNotContainKey it }
        artists["the-groovy-cellar"] shouldBe "The Groovy Cellar"
        lineupOf("schokoladen") shouldContainExactly listOf(Triple("the-groovy-cellar", "HEADLINER", 0))
    }

    @Test
    fun `leaves the convention with no act`() {
        lineupOf("so36").shouldBeEmpty()
    }

    @Test
    fun `bills the LARK night's acts in title order`() {
        lineupOf("lark") shouldContainExactly
            listOf(
                Triple("kyle-hall", "HEADLINER", 0),
                Triple("k15", "HEADLINER", 1),
                Triple("mamalia", "HEADLINER", 2),
                Triple("mauricio-fleury", "HEADLINER", 3),
                Triple("bulma-brief", "HEADLINER", 4)
            )
        artists()["mauricio-fleury"] shouldBe "Mauricio Fleury"
    }

    @Test
    fun `splits the Ritter Butzke row in place and reuses Vero`() {
        lineupOf("butzke") shouldContainExactly
            listOf(
                Triple("moonbootica", "DJ", 0),
                Triple("gloria-game-boyz", "DJ", 1),
                Triple("vero", "DJ", 2),
                Triple("kristin", "DJ", 3)
            )
        query("SELECT count(*) FROM artist WHERE name = 'Vero'") { it.getInt(1) }.single() shouldBe 1
        query("SELECT instagram_url FROM artist WHERE slug = 'vero'") { it.getString(1) }
            .single() shouldBe "https://www.instagram.com/veroonebln/"
        lineupOf("aeden") shouldContainExactly listOf(Triple("vero", "DJ", 0))
    }

    @Test
    fun `every act's slug is the slug of its name`() {
        val migration = File("src/main/resources/db/migration/V080__split_and_drop_september_junk_artists.sql").readText()
        val acts = ACT_ROW.findAll(migration).map { it.destructured }.toList()
        acts.size shouldBe 6
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

    /** One past night billing [lineup] as (artist slug, role), in billing order. */
    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg lineup: Pair<String, String>
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', '$sourceId', DATE '2026-09-27', '$sourceId' FROM venue WHERE slug = 'fixture'"
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
                "th-ink-about-that",
                "hum-w-kyle-hall-b2b-k15",
                "mamaliathe-first-lady-of-modern-funkft-mauricio-fleury",
                "gloria-game-boyz-feat-vero"
            )

        /** One `('fused-slug', 'slug', 'Name', <position>)` row of the act list. */
        val ACT_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'((?:[^']|'')+)',\s*\d+\)""")
    }
}
