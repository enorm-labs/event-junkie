package de.norm.events.event

import de.norm.events.flyway
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

/** Runs V184 (#3669) against planted event rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteFrannzClosureNoticeEventMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "183").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Frannz Club', 'frannz-club')")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES " +
                    "('Frannz Biergarten bleibt heute geschlossen!', 'frannz-biergarten-bleibt-heute-geschlossen'), " +
                    "('Rebel Yell', 'rebel-yell')"
            )
            plantEvent(statement, "frannz:10015", NOTICE_SLUG, "2026-09-07")
            // The same venue's next night, and a slug that only begins like the notice's.
            plantEvent(statement, "frannz:10016", "2026-09-11-frannz-club-rebel-yell", "2026-09-11")
            plantEvent(statement, "frannz:10017", "$NOTICE_SLUG-2", "2026-09-08")
            link("frannz-biergarten-bleibt-heute-geschlossen", NOTICE_SLUG)
            link("rebel-yell", "2026-09-11-frannz-club-rebel-yell")
        }
        flyway(postgres, "184").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `deletes the notice and keeps every other row`() {
        strings("SELECT slug FROM event") shouldContainExactlyInAnyOrder
            listOf("2026-09-11-frannz-club-rebel-yell", "$NOTICE_SLUG-2")
    }

    @Test
    fun `cascades to the notice's billing and leaves the artist to the orphan sweep`() {
        strings("SELECT a.slug FROM event_artist ea JOIN artist a ON a.id = ea.artist_id") shouldBe listOf("rebel-yell")
        strings("SELECT slug FROM artist") shouldContainExactlyInAnyOrder
            listOf("frannz-biergarten-bleibt-heute-geschlossen", "rebel-yell")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        slug: String,
        date: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id, event_type) " +
                "SELECT id, 'Fixture event', '$slug', DATE '$date', '$sourceId', 'CONCERT' FROM venue WHERE slug = 'frannz-club'"
        )
    }

    private fun link(
        artistSlug: String,
        eventSlug: String
    ) {
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO events.event_artist (event_id, artist_id) SELECT e.id, a.id FROM events.event e, events.artist a " +
                    "WHERE e.slug = '$eventSlug' AND a.slug = '$artistSlug'"
            )
        }
    }

    private fun strings(query: String): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(query).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private companion object {
        const val NOTICE_SLUG = "2026-09-07-frannz-club-frannz-biergarten-bleibt-heute-geschlossen"
    }
}
