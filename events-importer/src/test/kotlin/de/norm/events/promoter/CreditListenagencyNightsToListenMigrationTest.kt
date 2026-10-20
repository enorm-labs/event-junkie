package de.norm.events.promoter

import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V185 against planted rows on a database migrated to before it. */
class CreditListenagencyNightsToListenMigrationTest {
    @Test
    fun `moves the two named nights from Friendly Reminder to Listen and leaves the rest`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "184").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Columbia Theater', 'columbia-theater')")
                    statement.execute(
                        "INSERT INTO promoter (name, slug) VALUES ('Friendly Reminder', 'friendly-reminder'), " +
                            "('Listen', 'listen'), ('untoldency', 'untoldency')"
                    )
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, t.title, t.slug, t.d, t.src " +
                            "FROM venue, (VALUES ('Mar Malade', '2026-10-14-columbia-theater-mar-malade', DATE '2026-10-14', 'c:1'), " +
                            "('Mykki Blanco', '2026-10-11-columbia-theater-mykki-blanco', DATE '2026-10-11', 'c:2')) " +
                            "AS t(title, slug, d, src)"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p " +
                            "WHERE p.slug = 'friendly-reminder' " +
                            "OR (p.slug = 'untoldency' AND e.slug = '2026-10-14-columbia-theater-mar-malade')"
                    )
                }
                flyway(postgres, "185").migrate()

                creditsOf(connection, "2026-10-14-columbia-theater-mar-malade") shouldContainExactlyInAnyOrder
                    listOf("listen", "untoldency")
                creditsOf(connection, "2026-10-11-columbia-theater-mykki-blanco") shouldContainExactlyInAnyOrder
                    listOf("friendly-reminder")
            }
        }
    }

    private fun creditsOf(
        connection: Connection,
        event: String
    ): List<String> =
        rows(
            connection,
            "SELECT p.slug FROM events.event_promoter ep JOIN events.promoter p ON p.id = ep.promoter_id " +
                "JOIN events.event e ON e.id = ep.event_id WHERE e.slug = '$event'"
        ) { it.getString(1) }
}
