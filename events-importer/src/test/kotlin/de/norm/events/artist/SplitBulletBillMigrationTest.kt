package de.norm.events.artist

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
 * Runs V053 (#1818) against planted rows on a database migrated to just before it: the fused row
 * billing one past night, one of the two acts already holding a row, and an unrelated act that must
 * not move.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitBulletBillMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("52").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            plantArtist(statement, "New Candys (It, Fuzz Club) • Blke (De, Tonzonen)", "new-candys-it-fuzz-club-blke-de-tonzonen")
            // Blke already holds a row, billed by another night, and it carries an enrichment link.
            plantArtist(statement, "Blke", "blke")
            statement.execute("UPDATE artist SET bandcamp_url = 'https://blke.bandcamp.com/' WHERE slug = 'blke'")
            plantArtist(statement, "Mayflower Madame", "mayflower-madame")
            plantEvent(statement, "fused", "new-candys-it-fuzz-club-blke-de-tonzonen")
            plantEvent(statement, "other", "mayflower-madame", "blke")
        }
        flyway("53").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `bills both acts on the night the fused row held`() {
        eventsOf("new-candys") shouldContainExactlyInAnyOrder listOf("fused")
        eventsOf("blke") shouldContainExactlyInAnyOrder listOf("fused", "other")
        artists()["new-candys"] shouldBe "New Candys"
    }

    @Test
    fun `deletes the fused row and leaves every other act alone`() {
        artists().containsKey("new-candys-it-fuzz-club-blke-de-tonzonen") shouldBe false
        eventsOf("mayflower-madame") shouldContainExactlyInAnyOrder listOf("other")
    }

    @Test
    fun `reuses the act that already had a row, keeping what enrichment found`() {
        query("SELECT bandcamp_url FROM artist WHERE slug = 'blke'") { it.getString(1) }
            .single() shouldBe "https://blke.bandcamp.com/"
    }

    @Test
    fun `bills them in title order, under the role and stage the fused row carried`() {
        billingOf("fused") shouldContainExactlyInAnyOrder
            listOf(Triple("new-candys", "HEADLINER", 0), Triple("blke", "HEADLINER", 1))
    }

    @Test
    fun `every act's slug is the slug of its name`() {
        val migration = File("src/main/resources/db/migration/V053__split_urban_spree_bullet_bill.sql").readText()
        ACT_ROW
            .findAll(migration)
            .map { it.destructured }
            .filter { (slug, name, _) -> SlugGenerator.slugify(name.replace("''", "'")) != slug }
            .map { (slug, name, _) -> "'$slug' is named '$name'" }
            .toList()
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

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', '$sourceId', DATE '2026-08-31', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        for (slug in artistSlugs) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) " +
                    "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun artists(): Map<String, String> = query("SELECT slug, name FROM artist") { it.getString(1) to it.getString(2) }.toMap()

    private fun eventsOf(slug: String): List<String> =
        query(
            "SELECT e.source_id FROM event e JOIN event_artist ea ON ea.event_id = e.id " +
                "JOIN artist a ON a.id = ea.artist_id WHERE a.slug = '$slug'"
        ) { it.getString(1) }

    private fun billingOf(sourceId: String): List<Triple<String, String, Int>> =
        query(
            "SELECT a.slug, ea.role, ea.billing_order FROM event_artist ea " +
                "JOIN artist a ON a.id = ea.artist_id JOIN event e ON e.id = ea.event_id " +
                "WHERE e.source_id = '$sourceId'"
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
        /** One `('slug', 'Name', <order>)` row of the act list. */
        val ACT_ROW = Regex("""\('([^']+)',\s*'((?:[^']|'')+)',\s*(\d+)\)""")
    }
}
