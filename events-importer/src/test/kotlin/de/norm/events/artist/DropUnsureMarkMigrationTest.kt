package de.norm.events.artist

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V057 (#1843) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropUnsureMarkMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("56").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Octave?', 'octave')")
            // Same shape, another slug: the migration names one row only.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Therapy?', 'therapy')")
        }
        flyway("57").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `drops the mark from the minted row and nothing else`() {
        names()["octave"] shouldBe "Octave"
        names()["therapy"] shouldBe "Therapy?"
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun names(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug, name FROM artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap()
            }
        }
}
