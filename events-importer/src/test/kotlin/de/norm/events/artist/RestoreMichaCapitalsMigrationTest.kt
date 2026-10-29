package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V180 (#3623) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreMichaCapitalsMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "177").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Micha', 'micha')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Micha Moor', 'micha-moor')")
        }
        flyway(postgres, "180").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `restores the capitals of the micha row, and leaves a longer name alone`() {
        names()["micha"] shouldBe "MICHA"
        names()["micha-moor"] shouldBe "Micha Moor"
    }

    @Test
    fun `the stored name is the one the normalizer now gives`() {
        canonicalArtistName("Micha") shouldBe names()["micha"]
        artistSlugFor("MICHA") shouldBe "micha"
    }

    private fun names(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name FROM events.artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap()
            }
        }
}
