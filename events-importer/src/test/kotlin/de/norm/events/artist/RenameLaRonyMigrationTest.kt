package de.norm.events.artist

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V076 (#2208) against planted Junction Bar rows on a database migrated to before it. */
class RenameLaRonyMigrationTest {
    @Test
    fun `renames the minted row in place where no survivor exists`() {
        migrated(withSurvivor = false) { connection ->
            billing(connection) shouldBe mapOf(OCTOBER to "la-rony")
            artists(connection) shouldBe mapOf("la-rony" to "LA Rony")
        }
    }

    @Test
    fun `moves the night onto an existing survivor and deletes the loser`() {
        migrated(withSurvivor = true) { connection ->
            billing(connection) shouldBe mapOf(OCTOBER to "la-rony", NOVEMBER to "la-rony")
            artists(connection) shouldBe mapOf("la-rony" to "LA Rony")
        }
    }

    @Test
    fun `leaves a row a person has corrected since alone`() {
        migrated(withSurvivor = false, loserName = "LA Rony (acoustic)") { connection ->
            artists(connection) shouldBe mapOf("la-rony-acoustic-set" to "LA Rony (acoustic)")
        }
    }

    private fun migrated(
        withSurvivor: Boolean,
        loserName: String = "LA Rony acoustic set",
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "75").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Junction Bar', 'junction-bar')")
                    statement.execute(
                        "INSERT INTO event_source (venue_id, name, slug, url, source_type) " +
                            "SELECT id, 'Junction Bar', 'junction-bar', 'https://www.junction-bar.de/', 'JUNCTION_BAR' FROM venue"
                    )
                    plant(statement, OCTOBER, "2026-10-01", loserName, "la-rony-acoustic-set")
                    if (withSurvivor) plant(statement, NOVEMBER, "2026-11-12", "LA Rony", "la-rony")
                }
                flyway(postgres, "76").migrate()
                check(connection)
            }
        }
    }

    private fun plant(
        statement: Statement,
        sourceId: String,
        date: String,
        artist: String,
        slug: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, event_source_id, source_id, title, slug, event_date) " +
                "SELECT v.id, s.id, '$sourceId', 'LA RONY acoustic set', '$sourceId', DATE '$date' FROM venue v, event_source s"
        )
        statement.execute("INSERT INTO artist (name, slug) VALUES ('$artist', '$slug')")
        statement.execute(
            "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) " +
                "SELECT e.id, a.id, 'HEADLINER', 0, false FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
        )
    }

    private fun flyway(
        postgres: PostgreSQLContainer,
        target: String
    ): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun billing(connection: Connection): Map<String, String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT e.source_id, a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                        "JOIN events.artist a ON a.id = ea.artist_id"
                ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap() }
        }

    private fun artists(connection: Connection): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name FROM events.artist").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
            }
        }

    private companion object {
        const val OCTOBER = "junction-bar:2026-10-01-la-rony-acoustic-set"
        const val NOVEMBER = "junction-bar:2026-11-12-la-rony"
    }
}
