package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.scraper.EventSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs V227 (#4514) against the rows as production holds them: the cancelled night bills the series alone, and the
 * other night bills its two acts by a pinned lineup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropUfaFabrikSambaSeriesArtistMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "226").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('Samba Made in Berlin', 'samba-made-in-berlin'), " +
                    "('Terra Brasilis', 'terra-brasilis'), ('Dudu Tuccis Afoxé Berlin', 'dudu-tuccis-afoxe-berlin')"
            )
            statement.execute(
                "INSERT INTO event (venue_id, title, slug, event_date, source_id, status) SELECT id, 'Samba Made in Berlin - Fällt aus!', " +
                    "'cancelled-night', DATE '2026-11-06', 'ufa_fabrik:40415-2026-11-06-1930', 'CANCELLED' FROM venue"
            )
            statement.execute(
                "INSERT INTO event (venue_id, title, slug, event_date, source_id, pinned_fields) SELECT id, 'Samba Made in Berlin', " +
                    "'pinned-night', DATE '2026-11-07', 'ufa_fabrik:40407-2026-11-07-2000', '{lineup}' FROM venue"
            )
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) SELECT e.id, a.id FROM event e, artist a " +
                    "WHERE (e.slug = 'cancelled-night' AND a.slug = 'samba-made-in-berlin') " +
                    "OR (e.slug = 'pinned-night' AND a.slug <> 'samba-made-in-berlin')"
            )
        }
        flyway(postgres, "227").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `the only statement drops the slug the importer derives, for the source's own prefix`() {
        val statements = Files.readAllLines(MIGRATION).filterNot { it.isBlank() || it.startsWith("--") }
        statements shouldContainExactly
            listOf("SELECT drop_artist_billed_only_by('${artistSlugFor("Samba Made in Berlin")}', '${EventSource.UFA_FABRIK.sourceIdPrefix}');")
    }

    @Test
    fun `drops the series, and keeps the cancelled night with nobody billed`() {
        strings("SELECT slug FROM events.artist") shouldContainExactlyInAnyOrder listOf("terra-brasilis", "dudu-tuccis-afoxe-berlin")
        strings("SELECT status FROM events.event WHERE slug = 'cancelled-night'") shouldContainExactly listOf("CANCELLED")
        lineupOf("cancelled-night").shouldBeEmpty()
    }

    @Test
    fun `keeps the pinned night's lineup and its pin`() {
        lineupOf("pinned-night") shouldContainExactlyInAnyOrder listOf("terra-brasilis", "dudu-tuccis-afoxe-berlin")
        strings("SELECT unnest(pinned_fields) FROM events.event WHERE slug = 'pinned-night'") shouldContainExactly listOf("lineup")
    }

    private fun lineupOf(eventSlug: String): List<String> =
        strings(
            "SELECT a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.slug = '$eventSlug'"
        )

    private fun strings(sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V227__drop_ufafabrik_samba_series_artist.sql")
    }
}
