package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.scraper.EventSource
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Runs V226 (#4513) against the row as production holds it: one past, cancelled Huxleys night bills it alone. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropHuxleysRockLegendsArtistMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "225").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Rock Legends', 'rock-legends'), ('Rock Legend', 'rock-legend')")
            statement.execute(
                "INSERT INTO event (venue_id, title, slug, event_date, source_id, status) " +
                    "SELECT id, 'Rock Legends', 'rock-legends-night', DATE '2026-11-06', 'huxleys:2026-11-06-rock-legends', 'CANCELLED' FROM venue"
            )
            statement.execute("INSERT INTO event_artist (event_id, artist_id) SELECT e.id, a.id FROM event e, artist a")
        }
        flyway(postgres, "226").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `the only statement drops the slug the importer derives, for the source's own prefix`() {
        val statements = Files.readAllLines(MIGRATION).filterNot { it.isBlank() || it.startsWith("--") }
        statements shouldContainExactly
            listOf("SELECT drop_artist_billed_only_by('${artistSlugFor("Rock Legends")}', '${EventSource.HUXLEYS.sourceIdPrefix}');")
    }

    @Test
    fun `drops the row, and keeps the event and another act`() {
        strings("SELECT slug FROM events.artist") shouldContainExactly listOf("rock-legend")
        strings("SELECT status FROM events.event WHERE slug = 'rock-legends-night'") shouldContainExactly listOf("CANCELLED")
        strings("SELECT a.slug FROM events.event_artist ea JOIN events.artist a ON a.id = ea.artist_id") shouldContainExactly listOf("rock-legend")
    }

    private fun strings(sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V226__drop_huxleys_rock_legends_artist.sql")
    }
}
