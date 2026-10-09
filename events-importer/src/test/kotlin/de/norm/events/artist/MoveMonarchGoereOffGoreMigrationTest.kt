package de.norm.events.artist

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Runs V127 (#2942) against planted rows: `gore` billed by a Monarch night and by a UFO night, AMBIGUOUS
 * because the two are different acts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoveMonarchGoereOffGoreMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("125").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug, musicbrainz_match) VALUES ('Gore', 'gore', 'AMBIGUOUS')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('I Prevail', 'i-prevail')")
            plantEvent(statement, "monarch:2026-10-09-gore", "gore")
            plantEvent(statement, "ufo:2026-10-13-i-prevail", "i-prevail", "gore")
        }
        flyway("127").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `moves the Monarch night to Göre`() {
        lineupOf("monarch:2026-10-09-gore") shouldContainExactly listOf("goere")
        query("SELECT name FROM artist WHERE slug = 'goere'") { it.getString(1) } shouldContainExactly listOf("Göre")
    }

    @Test
    fun `leaves the other venue's night on Gore`() {
        lineupOf("ufo:2026-10-13-i-prevail") shouldContainExactly listOf("i-prevail", "gore")
    }

    @Test
    fun `lets MusicBrainz look at both rows again`() {
        query("SELECT musicbrainz_match FROM artist WHERE slug IN ('gore', 'goere')") { it.getString(1) } shouldContainExactly
            listOf("UNCHECKED", "UNCHECKED")
    }

    @Test
    fun `the new row's slug is the one the import derives for Göre`() {
        artistSlugFor("GÖRE") shouldBe "goere"
        artistSlugFor("Gore") shouldBe "gore"
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    /** One night billing the artists of [slugs], in billing order. */
    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg slugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', replace('$sourceId', ':', '-'), DATE '2026-10-09', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        slugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun lineupOf(sourceId: String): List<String> =
        query(
            "SELECT a.slug FROM event_artist ea JOIN artist a ON a.id = ea.artist_id JOIN event e ON e.id = ea.event_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { it.getString(1) }

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }
}
