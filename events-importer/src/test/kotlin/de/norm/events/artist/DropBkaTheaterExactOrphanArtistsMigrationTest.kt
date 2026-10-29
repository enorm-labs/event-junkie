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
import java.sql.Statement

/** Runs V177 (#3606) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropBkaTheaterExactOrphanArtistsMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "176").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for ((slug, mbid) in listOf(
                "mahoni" to "0e71673f-5a74-4f52-9402-b7535dcbc866",
                "evers" to "9d7a6e74-9762-4d66-ae13-43783ad37172",
                "eichhorn" to "e95727ac-07df-4e5b-af29-d313a7e1ede8"
            )) {
                statement.execute(
                    "INSERT INTO artist (name, slug, musicbrainz_id, musicbrainz_match) VALUES ('$slug', '$slug', '$mbid', 'EXACT')"
                )
            }
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Jacky und Mahoni', 'jacky-und-mahoni')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Evers & Eichhorn', 'evers-eichhorn')")
            plantEvent(statement, "bka_theater:1", "jacky-und-mahoni")
            plantEvent(statement, "bka_theater:2", "evers-eichhorn", "eichhorn")
            // Another venue billing the same slug is a real act there, so that row stays.
            plantEvent(statement, "so36:1", "evers")
        }
        flyway(postgres, "177").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `drops the EXACT halves that bill nothing or only BKA Theater, and keeps a half another source bills`() {
        slugs() shouldContainExactlyInAnyOrder listOf("evers", "jacky-und-mahoni", "evers-eichhorn")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-20', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        for (slug in artistSlugs) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) " +
                    "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun slugs(): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug FROM artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
