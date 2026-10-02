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

/** Runs V081 (#2350) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropUrbanSpreeCoBrandArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("80").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for ((name, slug) in listOf(
                "Human Tree x Urban Spree Klubnacht" to "human-tree-x-urban-spree-klubnacht",
                "aufnahme" to "aufnahme",
                "wiedergabe X Urban Spree" to "wiedergabe-x-urban-spree",
                "Bam Bam’s Boogie" to "bam-bams-boogie"
            )) {
                statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
            }
            plantEvent(statement, "urban_spree:concerts/human-tree-x-urban-spree-klubnacht", "human-tree-x-urban-spree-klubnacht", "bam-bams-boogie")
            plantEvent(statement, "urban_spree:concerts/aufnahme-wiedergabe-x-urban-spree", "aufnahme", "wiedergabe-x-urban-spree")
            plantEvent(statement, "urban_spree:concerts/human-tree-night", "human-tree-x-urban-spree-klubnacht")
            // `Aufnahme` billed at another venue may be a real act there, so that row stays.
            plantEvent(statement, "so36:1", "aufnahme")
        }
        flyway("81").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `removes the rows that bill only Urban Spree events, and keeps the events and the rest`() {
        slugs("artist") shouldContainExactlyInAnyOrder listOf("bam-bams-boogie", "aufnahme")
        slugs("event") shouldContainExactlyInAnyOrder
            listOf(
                "urban_spree-concerts/human-tree-x-urban-spree-klubnacht",
                "urban_spree-concerts/aufnahme-wiedergabe-x-urban-spree",
                "urban_spree-concerts/human-tree-night",
                "so36-1"
            )
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-09', '$sourceId' FROM venue WHERE slug = 'fixture'"
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

    private fun slugs(table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug FROM $table").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
