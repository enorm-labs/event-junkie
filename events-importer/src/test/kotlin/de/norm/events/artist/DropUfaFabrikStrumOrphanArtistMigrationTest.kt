package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V189 (#3731) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropUfaFabrikStrumOrphanArtistMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "188").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO artist (name, slug, musicbrainz_id, musicbrainz_match) " +
                    "VALUES ('Strum', 'strum', '3e4241ef-521e-490b-be11-5f54457174cd', 'EXACT')"
            )
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Strummer', 'strummer')")
            statement.execute(
                "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                    "SELECT id, 'Berliner Ukulele Sing & Strum', 'ufa-1', DATE '2026-10-14', 'ufa_fabrik:40287-2026-10-14' FROM venue"
            )
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) SELECT e.id, a.id FROM event e, artist a WHERE e.slug = 'ufa-1' AND a.slug = 'strum'"
            )
        }
        flyway(postgres, "189").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `drops the EXACT half only ufaFabrik billed, and keeps a longer name`() {
        slugs() shouldContainExactlyInAnyOrder listOf("strummer")
    }

    private fun slugs(): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug FROM artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
