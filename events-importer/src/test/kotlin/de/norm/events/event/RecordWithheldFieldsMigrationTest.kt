package de.norm.events.event

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V069 (#2130) against planted events on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordWithheldFieldsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("68").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO venue (name, slug, address, city, postal_code) VALUES ('Venue', 'venue', 'Somewhere 1', 'Berlin', '10999')"
            )
            statement.execute(
                "INSERT INTO event_source (venue_id, name, slug, url, source_type, description_licence, image_licence) " +
                    "SELECT v.id, s.slug, s.slug, s.url, 'WEBSITE', s.d, s.i FROM venue v, (VALUES " +
                    "('prohibited', 'https://a.example', 'PROHIBITED', 'PROHIBITED'), " +
                    "('text-only', 'https://b.example', 'PROHIBITED', 'UNCLEAR'), " +
                    "('unclear', 'https://c.example', 'UNCLEAR', NULL)) AS s(slug, url, d, i)"
            )
            statement.execute(
                "INSERT INTO event (venue_id, event_source_id, source_id, title, slug, event_date) " +
                    "SELECT v.id, (SELECT id FROM event_source WHERE slug = e.src), e.key, e.slug, e.slug, CURRENT_DATE FROM venue v, (VALUES " +
                    "('prohibited', 'p:1', 'a'), ('text-only', 't:1', 'b'), ('unclear', 'u:1', 'c'), (NULL, 'm:1', 'd')) AS e(src, key, slug)"
            )
        }
        flyway("69").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `marks each field withheld only where the source's licence prohibits it`() {
        flags() shouldBe
            mapOf(
                "a" to (true to true),
                "b" to (true to false),
                "c" to (false to false),
                "d" to (false to false)
            )
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun flags(): Map<String, Pair<Boolean, Boolean>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, description_withheld, image_withheld FROM events.event").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to (rows.getBoolean(2) to rows.getBoolean(3)) else null }.toMap()
            }
        }
}
