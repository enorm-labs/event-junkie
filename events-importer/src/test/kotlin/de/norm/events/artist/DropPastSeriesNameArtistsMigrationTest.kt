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

/** Runs V059 (#1978) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropPastSeriesNameArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("58").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Thrash Talk Vol 5', 'thrash-talk-vol-5')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('In The Mountains', 'in-the-mountains')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Rockstar Girlfriends', 'rockstar-girlfriends')")
            // A real act whose name merely contains the letters of a volume: the migration names slugs only.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Volbeat', 'volbeat')")
        }
        flyway("59").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `removes the three series names and nothing else`() {
        slugs() shouldContainExactlyInAnyOrder listOf("volbeat")
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
