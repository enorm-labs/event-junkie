package de.norm.events.translation

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V239 against planted rows on a database migrated to before it. */
class RepairMachineTranslationClosingQuotesMigrationTest {
    @Test
    fun `repairs machine translations and leaves the rest alone`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "238").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Schokoladen', 'schokoladen')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id, " +
                            "description_alt, description_alt_language, description_alt_origin) " +
                            "SELECT v.id, 'Night ' || n, 'night-' || n, DATE '2026-12-01', 's:' || n, t, l, o " +
                            "FROM venue v, (VALUES " +
                            "(1, 'Mit „Beto\" und „Tony\".', 'de', 'MACHINE'), " +
                            "(2, 'They call it „trippy pop\".', 'en', 'MACHINE'), " +
                            "(3, 'Mit „Beto\" vom Haus.', 'de', 'PUBLISHER'), " +
                            "(4, 'Mit „Beto“ und \"Tony\".', 'de', 'MACHINE')) AS r(n, t, l, o)"
                    )
                }
                flyway(postgres, "239").migrate()

                rows(connection, "SELECT description_alt FROM events.event ORDER BY source_id") { it.getString(1) } shouldContainExactly
                    listOf(
                        "Mit „Beto“ und „Tony“.",
                        "They call it “trippy pop”.",
                        "Mit „Beto\" vom Haus.",
                        "Mit „Beto“ und \"Tony\"."
                    )
            }
        }
    }
}
