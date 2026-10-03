package de.norm.events.artist

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
 * Runs V090 (#2416) against planted artist rows on a database migrated to before it. One series
 * row bills only its own source and goes; the other is also billed by a second venue and stays.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropAbraxasPartyAndAufTaktArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("89").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('Abraxas Party', 'abraxas-party'), " +
                    "('Auf Takt! Das Podcast-konzert', 'auf-takt-das-podcast-konzert'), ('Arch Enemy', 'arch-enemy')"
            )
            plantEvent(statement, "binuu:23hcylqsvqlinm1", "abraxas-party")
            plantEvent(statement, "heimathafen:31151-2026-10-03-2000", "auf-takt-das-podcast-konzert")
            plantEvent(statement, "so36:1", "auf-takt-das-podcast-konzert")
            plantEvent(statement, "binuu:archenemy", "arch-enemy")
        }
        flyway("90").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `removes a row that bills only its own source, keeps one billed elsewhere, and keeps every event`() {
        slugs("artist") shouldContainExactlyInAnyOrder listOf("auf-takt-das-podcast-konzert", "arch-enemy")
        slugs("event") shouldContainExactlyInAnyOrder
            listOf("binuu-23hcylqsvqlinm1", "heimathafen-31151-2026-10-03-2000", "so36-1", "binuu-archenemy")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        artistSlug: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-03', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        statement.execute(
            "INSERT INTO event_artist (event_id, artist_id) " +
                "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$artistSlug'"
        )
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun slugs(table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug FROM $table").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
