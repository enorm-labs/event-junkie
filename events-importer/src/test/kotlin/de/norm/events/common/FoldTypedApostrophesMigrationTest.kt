package de.norm.events.common

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V072 (#2174) against planted artist, promoter and event rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoldTypedApostrophesMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("71").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('Dingo`s Dream', 'dingo-s-dream'), ('Romp`n`Stomp', 'romp-n-stomp'), " +
                    "('pris´n break', 'pris-n-break'), ('´s Wirtshaus', 's-wirtshaus'), ('Arm''s Length', 'arm-s-length')"
            )
            statement.execute("INSERT INTO promoter (name, slug) VALUES ('Luv`n Musiq', 'luv-n-musiq')")
            statement.execute(
                "INSERT INTO venue (name, slug, address, city, postal_code) VALUES ('Venue', 'venue', 'Somewhere 1', 'Berlin', '10999')"
            )
            statement.execute(
                "INSERT INTO event_source (venue_id, name, slug, url, source_type) " +
                    "SELECT id, 'source', 'source', 'https://a.example', 'WEBSITE' FROM venue"
            )
            statement.execute(
                "INSERT INTO event (venue_id, event_source_id, source_id, title, slug, event_date) " +
                    "SELECT v.id, s.id, 's:1', 'PANSY´S HALLOWEEN', 'pansy-s-halloween', CURRENT_DATE FROM venue v, event_source s"
            )
        }
        flyway("72").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `folds each mark between two letters, every one of them in a name`() {
        column("artist", "name") shouldBe
            mapOf(
                "dingo-s-dream" to "Dingo's Dream",
                "romp-n-stomp" to "Romp'n'Stomp",
                "pris-n-break" to "pris'n break",
                "s-wirtshaus" to "´s Wirtshaus",
                "arm-s-length" to "Arm's Length"
            )
    }

    @Test
    fun `folds promoter names and event titles the same way`() {
        column("promoter", "name") shouldBe mapOf("luv-n-musiq" to "Luv'n Musiq")
        column("event", "title") shouldBe mapOf("pansy-s-halloween" to "PANSY'S HALLOWEEN")
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun column(
        table: String,
        name: String
    ): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, $name FROM events.$table").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
            }
        }
}
