package de.norm.events.artist

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Runs V091 (#2418) against a planted `performances-by` row on a database migrated to before it.
 * Billed only by Renate, the row goes; billed by another venue too, it stays. The events stay.
 */
class DropRenatePerformancesByArtistMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeEach
    fun migrateToBefore() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("90").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('Performances by', 'performances-by'), " +
                    "('Tallest Woman Alive', 'tallest-woman-alive')"
            )
            plantEvent(statement, "renate:2026-10-03-renate-klubnacht", "performances-by")
            plantEvent(statement, "renate:2026-10-03-renate-klubnacht", "tallest-woman-alive")
        }
    }

    @AfterEach
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `removes the label row billed only by Renate and keeps the event`() {
        flyway("91").migrate()

        slugs("artist") shouldContainExactlyInAnyOrder listOf("tallest-woman-alive")
        slugs("event") shouldContainExactlyInAnyOrder listOf("renate-2026-10-03-renate-klubnacht")
    }

    @Test
    fun `keeps the row when another venue bills it too`() {
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            plantEvent(statement, "so36:1", "performances-by")
        }
        flyway("91").migrate()

        slugs("artist") shouldContainExactlyInAnyOrder listOf("performances-by", "tallest-woman-alive")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        artistSlug: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-03', '$sourceId' FROM venue WHERE slug = 'fixture' " +
                "ON CONFLICT DO NOTHING"
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
