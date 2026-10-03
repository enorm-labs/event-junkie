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

/** Runs V085 (#2398) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropModusComedySeriesArtistMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("84").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Modus Comedy', 'modus-comedy'), ('Mia Morgan', 'mia-morgan')")
            plantEvent(statement, "modus:211026-ModusComedy", "modus-comedy")
            plantEvent(statement, "modus:281026-ModusComedy", "modus-comedy")
            plantEvent(statement, "modus:011026-MiaMorgan", "mia-morgan")
        }
        flyway("85").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `removes the series row that bills only Modus events, and keeps the events and the rest`() {
        slugs("artist") shouldContainExactlyInAnyOrder listOf("mia-morgan")
        slugs("event") shouldContainExactlyInAnyOrder listOf("modus-211026-ModusComedy", "modus-281026-ModusComedy", "modus-011026-MiaMorgan")
    }

    @Test
    fun `keeps a same-slug row that another venue bills`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { other ->
            other.start()
            DriverManager.getConnection(other.jdbcUrl, other.username, other.password).use { conn ->
                flyway("84", other).migrate()
                conn.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Modus Comedy', 'modus-comedy')")
                    plantEvent(statement, "modus:211026-ModusComedy", "modus-comedy")
                    plantEvent(statement, "so36:1", "modus-comedy")
                }
                flyway("85", other).migrate()
                slugs("artist", conn) shouldContainExactlyInAnyOrder listOf("modus-comedy")
            }
        }
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-21', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        for (slug in artistSlugs) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) " +
                    "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun flyway(
        target: String,
        container: PostgreSQLContainer = postgres
    ): Flyway =
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .schemas("events")
            .target(target)
            .load()

    private fun slugs(
        table: String,
        conn: Connection = connection
    ): List<String> =
        conn.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug FROM $table").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
