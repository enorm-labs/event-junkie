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

/** Runs V183 (#3642) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropMcUncutGemTitleMarkMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "181").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('MC Uncut Gem!', 'mc-uncut-gem')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('CC:DISCO!', 'cc-disco')")
        }
        flyway(postgres, "183").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `drops the title's mark from mc uncut gem, and leaves an act's own mark alone`() {
        names()["mc-uncut-gem"] shouldBe "MC Uncut Gem"
        names()["cc-disco"] shouldBe "CC:DISCO!"
    }

    @Test
    fun `the stored name is the one the normalizer now gives`() {
        canonicalArtistName("MC UNCUT GEM") shouldBe names()["mc-uncut-gem"]
        artistSlugFor("MC Uncut Gem") shouldBe "mc-uncut-gem"
    }

    private fun names(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name FROM events.artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap()
            }
        }
}
