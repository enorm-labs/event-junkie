package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.scraper.stripArtistSuffix
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V181 against planted rows on a database migrated to before it. */
class MergeBackingBandTwinsMigrationTest {
    @Test
    fun `every loser is the tailed name and every survivor the name the importer now bills`() {
        val rows = MERGE_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues.drop(1) }.toList()
        rows.size shouldBe 3
        rows.forEach { (loser, survivor, name) ->
            artistSlugFor(STORED_NAMES.getValue(loser)) shouldBe loser
            artistSlugFor(name) shouldBe survivor
            canonicalArtistName(stripArtistSuffix(STORED_NAMES.getValue(loser))) shouldBe name
        }
    }

    @Test
    fun `moves the loser's nights onto the survivor and deletes the loser`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "180").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Kesselhaus', 'kesselhaus')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Wenzel & Band', 'wenzel-band'), ('Wenzel', 'wenzel')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Christoph Titz & Band', 'christoph-titz-band')")
                    plant(statement, "k1", "wenzel-band")
                    // A night that bills both spellings keeps one link.
                    plant(statement, "k2", "wenzel", "wenzel-band")
                    // A survivor that does not exist yet is created.
                    plant(statement, "k3", "christoph-titz-band")
                }
                flyway(postgres, "181").migrate()

                billing(connection, "k1") shouldBe listOf("wenzel")
                billing(connection, "k2") shouldBe listOf("wenzel")
                billing(connection, "k3") shouldBe listOf("christoph-titz")
                slugs(connection) shouldContainExactlyInAnyOrder listOf("wenzel", "christoph-titz")
            }
        }
    }

    private fun plant(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '$sourceId', DATE '2026-12-10', 'kesselhaus:$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order FROM event e, artist a WHERE e.slug = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun billing(
        connection: Connection,
        eventSlug: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.slug = '$eventSlug' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V181__merge_backing_band_twins.sql")

        /** One `(loser, survivor, survivor_name)` row of the merge list. */
        val MERGE_ROW = Regex("""\('([^']+)',\s*'([^']+)',\s*'([^']+)'\)""")

        /** Each loser's name as production stores it. */
        val STORED_NAMES =
            mapOf(
                "bial-hclap-band" to "Bial Hclap & Band",
                "christoph-titz-band" to "Christoph Titz & Band",
                "wenzel-band" to "Wenzel & Band"
            )
    }
}
