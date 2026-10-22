package de.norm.events.artist

import de.norm.events.flyway
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V187 against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreFilCapitalsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "186").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Fil', 'fil')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Filow', 'filow')")
        }
        flyway(postgres, "187").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `restores FIL's capitals and pins them, and leaves a longer name alone`() {
        val rows = rows()
        assertSoftly {
            rows["fil"] shouldBe ("FIL" to true)
            rows["filow"] shouldBe ("Filow" to false)
        }
    }

    @Test
    fun `the stored name is the one the normalizer now gives the billing, under the slug it already has`() {
        canonicalArtistName("FIL") shouldBe rows().getValue("fil").first
        artistSlugFor("FIL") shouldBe "fil"
    }

    private fun rows(): Map<String, Pair<String, Boolean>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name, name_pinned FROM events.artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to (rs.getString(2) to rs.getBoolean(3)) else null }.toMap()
            }
        }
}
