package de.norm.events.genretag

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V124 (#2924) against planted genre tags on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeshoutGenreTagNamesMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("123").migrate()
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO events.genre_tag (name, slug) VALUES ('TRANCE', 'trance'), ('EARLY HARDCORE', 'early-hardcore'), " +
                    "('Gabber', 'gabber'), ('EBM', 'ebm')"
            )
        }
        flyway("124").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `renames a shouted tag, and leaves a corrected one and an acronym alone`() {
        names() shouldBe
            mapOf(
                "trance" to "Trance",
                "early-hardcore" to "Early Hardcore",
                "gabber" to "Gabber",
                "ebm" to "EBM"
            )
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
            statement.executeQuery("SELECT slug, name FROM events.genre_tag").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
            }
        }
}
