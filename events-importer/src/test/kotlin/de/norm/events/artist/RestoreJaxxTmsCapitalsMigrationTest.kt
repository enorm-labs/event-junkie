package de.norm.events.artist

import de.norm.events.flyway
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs V210 (#4224) against planted artist rows on a database migrated to before it, and again on a second
 * database whose `jaxx-tms` row was renamed by hand.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreJaxxTmsCapitalsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection
    private lateinit var renamed: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "209").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Jaxx Tms', 'jaxx-tms')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Jaxx', 'jaxx')")
        }
        flyway(postgres, "210").migrate()

        connection.createStatement().use { it.execute("CREATE DATABASE renamed") }
        val renamedUrl = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/renamed")
        renamed = DriverManager.getConnection(renamedUrl, postgres.username, postgres.password)
        flywayOn(renamedUrl, "209").migrate()
        renamed.createStatement().use { it.execute("INSERT INTO events.artist (name, slug) VALUES ('Jaxx T.M.S.', 'jaxx-tms')") }
        flywayOn(renamedUrl, "210").migrate()
    }

    @AfterAll
    fun stop() {
        renamed.close()
        connection.close()
        postgres.stop()
    }

    @Test
    fun `restores the capitals of the row and pins it, and leaves another name alone`() {
        val rows = rows(connection)
        assertSoftly {
            rows["jaxx-tms"] shouldBe ("Jaxx TMS" to true)
            rows["jaxx"] shouldBe ("Jaxx" to false)
        }
    }

    @Test
    fun `leaves a row renamed by hand as it is`() {
        rows(renamed)["jaxx-tms"] shouldBe ("Jaxx T.M.S." to false)
    }

    @Test
    fun `the stored name is the one the normalizer now gives the billing, under the slug it already has`() {
        canonicalArtistName("JAXX TMS") shouldBe rows(connection).getValue("jaxx-tms").first
        artistSlugFor("JAXX TMS") shouldBe "jaxx-tms"
    }

    private fun flywayOn(
        url: String,
        target: String
    ): Flyway =
        Flyway
            .configure()
            .dataSource(url, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun rows(on: Connection): Map<String, Pair<String, Boolean>> =
        on.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name, name_pinned FROM events.artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to (rs.getString(2) to rs.getBoolean(3)) else null }.toMap()
            }
        }
}
