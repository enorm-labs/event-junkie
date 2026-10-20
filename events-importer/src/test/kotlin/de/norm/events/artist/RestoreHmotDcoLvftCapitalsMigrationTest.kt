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

/** Runs V182 (#3641) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreHmotDcoLvftCapitalsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "181").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Hmot', 'hmot')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('D''co', 'd-co')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Lvft', 'lvft')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Hmot Club', 'hmot-club')")
        }
        flyway(postgres, "182").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `restores the capitals of the three rows and pins them, and leaves a longer name alone`() {
        val rows = rows()
        assertSoftly {
            rows["hmot"] shouldBe ("HMOT" to true)
            rows["d-co"] shouldBe ("D'CO" to true)
            rows["lvft"] shouldBe ("LVFT" to true)
            rows["hmot-club"] shouldBe ("Hmot Club" to false)
        }
    }

    @Test
    fun `each stored name is the one the normalizer now gives the billing, under the slug it already has`() {
        val rows = rows()
        assertSoftly {
            listOf("HMOT" to "hmot", "D'CO" to "d-co", "LVFT" to "lvft").forEach { (billed, slug) ->
                canonicalArtistName(billed) shouldBe rows.getValue(slug).first
                artistSlugFor(billed) shouldBe slug
            }
        }
    }

    private fun rows(): Map<String, Pair<String, Boolean>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name, name_pinned FROM events.artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to (rs.getString(2) to rs.getBoolean(3)) else null }.toMap()
            }
        }
}
