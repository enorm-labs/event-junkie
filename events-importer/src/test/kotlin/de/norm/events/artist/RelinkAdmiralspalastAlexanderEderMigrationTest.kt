package de.norm.events.artist

import de.norm.events.flyway
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V144 (#3150) against the past Admiralspalast night it relinks, on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RelinkAdmiralspalastAlexanderEderMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "142").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Alexander Eder Unplugged', 'alexander-eder-unplugged')")
            plantEvent(statement, NIGHT, "alexander-eder-unplugged")
        }
        flyway(postgres, "144").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `bills the act in the old row's place and drops the old row`() {
        query(
            "SELECT a.slug, a.name, ea.role, ea.billing_order FROM event_artist ea JOIN artist a ON a.id = ea.artist_id " +
                "JOIN event e ON e.id = ea.event_id WHERE e.source_id = '$NIGHT'"
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getInt(4).toString()) } shouldContainExactly
            listOf(listOf("alexander-eder", "Alexander Eder", "HEADLINER", "0"))
        query("SELECT slug FROM artist") { it.getString(1) } shouldContainExactlyInAnyOrder listOf("alexander-eder")
    }

    @Test
    fun `the new row's slug is the slug of its name`() {
        SlugGenerator.slugify("Alexander Eder") shouldBe "alexander-eder"
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        artistSlug: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', 'fixture-night', DATE '2026-10-09', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        statement.execute(
            "INSERT INTO event_artist (event_id, artist_id, title_derived) " +
                "SELECT e.id, a.id, TRUE FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$artistSlug'"
        )
    }

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }

    private companion object {
        const val NIGHT = "admiralspalast:alexander-eder-unplugged-die-akustik-tour-live-mit-band-2026-10-09-2000"
    }
}
