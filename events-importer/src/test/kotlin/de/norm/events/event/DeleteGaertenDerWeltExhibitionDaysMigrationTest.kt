package de.norm.events.event

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V102 (#2655) against planted event rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteGaertenDerWeltExhibitionDaysMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("101").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Agnes Obel', 'agnes-obel')")
            statement.execute("INSERT INTO promoter (name, slug) VALUES ('Grün Berlin', 'grun-berlin')")
            plantEvent(statement, "gaerten_der_welt:2026-09-01_0900/zwischen-himmel-und-erde-ausstellung", "2026-09-01", "EXHIBITION")
            plantEvent(statement, "gaerten_der_welt:2026-10-04_0900/zwischen-himmel-und-erde-ausstellung", "2026-10-04", "EXHIBITION")
            // A day row with links, so the delete has to cascade.
            statement.execute(
                "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p " +
                    "WHERE e.source_id = 'gaerten_der_welt:2026-10-04_0900/zwischen-himmel-und-erde-ausstellung'"
            )
            // The run row, a one-off concert with a dated id, another exhibition and another venue's row stay.
            plantEvent(statement, "gaerten_der_welt:zwischen-himmel-und-erde-ausstellung", "2026-09-02", "EXHIBITION")
            plantEvent(statement, "gaerten_der_welt:2026-08-15_1900/agnes-obel", "2026-08-15", "CONCERT")
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) SELECT e.id, a.id FROM event e, artist a " +
                    "WHERE e.source_id = 'gaerten_der_welt:2026-08-15_1900/agnes-obel'"
            )
            plantEvent(statement, "gaerten_der_welt:2026-10-05_1000/eine-andere-ausstellung", "2026-10-05", "EXHIBITION")
            plantEvent(statement, "silent_green:2026-10-04_0900/zwischen-himmel-und-erde-ausstellung", "2026-10-06", "EXHIBITION")
        }
        flyway("102").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `deletes the exhibition's daily rows and keeps the run, the concert and every other row`() {
        strings("SELECT source_id FROM event") shouldContainExactlyInAnyOrder
            listOf(
                "gaerten_der_welt:zwischen-himmel-und-erde-ausstellung",
                "gaerten_der_welt:2026-08-15_1900/agnes-obel",
                "gaerten_der_welt:2026-10-05_1000/eine-andere-ausstellung",
                "silent_green:2026-10-04_0900/zwischen-himmel-und-erde-ausstellung"
            )
    }

    @Test
    fun `cascades to the deleted rows' links and keeps the concert's`() {
        strings("SELECT count(*)::text FROM event_promoter") shouldBe listOf("0")
        strings("SELECT count(*)::text FROM event_artist") shouldBe listOf("1")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        date: String,
        type: String
    ) {
        val slug = sourceId.replace(Regex("[^a-z0-9]+"), "-")
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id, event_type) " +
                "SELECT id, 'Fixture event', '$slug', DATE '$date', '$sourceId', '$type' FROM venue WHERE slug = 'fixture'"
        )
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun strings(query: String): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(query).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
