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

/** Runs V064 (#2063) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropHauteFreddySplitArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("63").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for ((name, slug) in listOf("Haute" to "haute", "Freddy" to "freddy", "Cabbage the Clown" to "cabbage-the-clown")) {
                statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
            }
            plantEvent(statement, "huxleys-1339", "HAUTE & FREDDY", "haute", "freddy", "cabbage-the-clown")
            // A `Freddy` billed on another night is a different act, so the row stays.
            plantEvent(statement, "other-night", "Freddy Live", "freddy")
        }
        flyway("64").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `removes a split row that bills only the duo's night, and keeps the rest`() {
        slugs() shouldContainExactlyInAnyOrder listOf("freddy", "cabbage-the-clown")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        title: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$title', '$sourceId', DATE '2026-11-04', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        for (slug in artistSlugs) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) " +
                    "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun slugs(): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug FROM artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
