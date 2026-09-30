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

/** Runs V071 (#2167) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropGretchenNoticeArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("70").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for ((name, slug) in listOf(
                "Mop Mop" to "mop-mop",
                "Tickets können da zurückgegeben werden, wo sie gekauft wurden." to
                    "tickets-konnen-da-zuruckgegeben-werden-wo-sie-gekauft-wurden",
                "Wir suchen einen neuen Termin, um das Konzert nachzuholen." to
                    "wir-suchen-einen-neuen-termin-um-das-konzert-nachzuholen",
                "Es tut uns sehr leid." to "es-tut-uns-sehr-leid"
            )) {
                statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
            }
            plantEvent(
                statement,
                "gretchen:3518",
                "mop-mop",
                "tickets-konnen-da-zuruckgegeben-werden-wo-sie-gekauft-wurden",
                "wir-suchen-einen-neuen-termin-um-das-konzert-nachzuholen",
                "es-tut-uns-sehr-leid"
            )
            // The same sentence billed at another venue is not this notice, so that row stays.
            plantEvent(statement, "so36:1", "es-tut-uns-sehr-leid")
        }
        flyway("71").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `removes the notice rows that bill only Gretchen events, and keeps the rest`() {
        slugs() shouldContainExactlyInAnyOrder listOf("mop-mop", "es-tut-uns-sehr-leid")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-09-30', '$sourceId' FROM venue WHERE slug = 'fixture'"
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
