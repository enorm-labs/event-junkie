package de.norm.events.artist

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

/** Calls the V092 function (#2467) against planted rows, each case on its own slug so the calls stay independent. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropArtistBilledOnlyByMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target("92")
            .load()
            .migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for (slug in listOf("performances-by", "aufnahme", "co-brand", "bystander")) {
                statement.execute("INSERT INTO artist (name, slug) VALUES ('$slug', '$slug')")
            }
            plantEvent(statement, "renate:1", "performances-by", "bystander")
            plantEvent(statement, "renate:2", "performances-by")
            plantEvent(statement, "urban_spree:1", "aufnahme")
            plantEvent(statement, "so36:1", "aufnahme")
            plantEvent(statement, "urban_spree:2", "co-brand")
            // `_` must not match any character, or this row would count as Urban Spree's.
            plantEvent(statement, "urbanxspree:1", "co-brand")
        }
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `drops a row billed only by the source, and keeps its events and their other acts`() {
        drop("performances-by", "renate:") shouldBe 1
        artistSlugs() shouldContainExactlyInAnyOrder listOf("aufnahme", "co-brand", "bystander")
        lineupOf("renate:1") shouldContainExactlyInAnyOrder listOf("bystander")
        lineupOf("renate:2") shouldBe emptyList()
    }

    @Test
    fun `keeps a row that another source bills too`() {
        drop("aufnahme", "urban_spree:") shouldBe 0
        lineupOf("so36:1") shouldContainExactlyInAnyOrder listOf("aufnahme")
    }

    @Test
    fun `reads an underscore in the prefix literally`() {
        drop("co-brand", "urban_spree:") shouldBe 0
        lineupOf("urbanxspree:1") shouldContainExactlyInAnyOrder listOf("co-brand")
    }

    @Test
    fun `deletes nothing for an unknown slug`() {
        drop("no-such-artist", "renate:") shouldBe 0
    }

    private fun drop(
        slug: String,
        sourcePrefix: String
    ): Int =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT drop_artist_billed_only_by('$slug', '$sourcePrefix')").use { rs ->
                rs.next()
                rs.getInt(1)
            }
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

    private fun artistSlugs(): List<String> = strings("SELECT slug FROM artist")

    private fun lineupOf(sourceId: String): List<String> =
        strings(
            "SELECT a.slug FROM event_artist ea JOIN event e ON e.id = ea.event_id JOIN artist a ON a.id = ea.artist_id " +
                "WHERE e.source_id = '$sourceId'"
        )

    private fun strings(sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
