package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
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

/**
 * Runs V192 (#3856) against planted rows: `maschine` billed by an Admiralspalast concert and by a Der Weiße Hase
 * night, AMBIGUOUS because the two are different acts, and the concert tagged Techno from the night.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoveAdmiralspalastMaschineOffTheDjMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "191").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug, musicbrainz_match) VALUES ('Maschine', 'maschine', 'AMBIGUOUS')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Puk', 'puk')")
            statement.execute("INSERT INTO genre_tag (name, slug) VALUES ('Techno', 'techno'), ('Schlager', 'schlager')")
            plantEvent(statement, "derweissehase:2026-10-10-maschine-24-7", "maschine", "puk")
            plantEvent(statement, "admiralspalast:2026-10-24-maschine", "maschine")
            tag(statement, "derweissehase:2026-10-10-maschine-24-7", "techno", "SOURCE")
            tag(statement, "admiralspalast:2026-10-24-maschine", "techno", "ARTIST")
            tag(statement, "admiralspalast:2026-10-24-maschine", "schlager", "SOURCE")
        }
        flyway(postgres, "192").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `moves the Admiralspalast concert to a row of its own`() {
        lineupOf("admiralspalast:2026-10-24-maschine") shouldContainExactly listOf("maschine-2")
        query("SELECT name FROM artist WHERE slug = 'maschine-2'") { it.getString(1) } shouldContainExactly listOf("Maschine")
    }

    @Test
    fun `leaves the Der Weisse Hase night on the DJ's row`() {
        lineupOf("derweissehase:2026-10-10-maschine-24-7") shouldContainExactly listOf("maschine", "puk")
    }

    @Test
    fun `drops only the tag the concert derived from the shared row`() {
        tagsOf("admiralspalast:2026-10-24-maschine") shouldContainExactly listOf("schlager:SOURCE")
        tagsOf("derweissehase:2026-10-10-maschine-24-7") shouldContainExactly listOf("techno:SOURCE")
    }

    @Test
    fun `lets MusicBrainz look at both rows again`() {
        query("SELECT musicbrainz_match FROM artist WHERE slug IN ('maschine', 'maschine-2')") { it.getString(1) } shouldContainExactlyInAnyOrder
            listOf("UNCHECKED", "UNCHECKED")
    }

    @Test
    fun `the new row's slug is the one the import derives for the Admiralspalast billing`() {
        artistSlugFor("MASCHINE", "admiralspalast:2026-10-24-maschine") shouldBe "maschine-2"
        artistSlugFor("Maschine", "derweissehase:2026-10-10-maschine-24-7") shouldBe "maschine"
        artistSlugFor("Maschine") shouldBe "maschine"
    }

    /** One night billing the artists of [slugs], in billing order. */
    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg slugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', replace('$sourceId', ':', '-'), DATE '2026-10-24', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        slugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun tag(
        statement: Statement,
        sourceId: String,
        tagSlug: String,
        origin: String
    ) {
        statement.execute(
            "INSERT INTO event_genre_tag (event_id, genre_tag_id, origin) " +
                "SELECT e.id, g.id, '$origin' FROM event e, genre_tag g WHERE e.source_id = '$sourceId' AND g.slug = '$tagSlug'"
        )
    }

    private fun lineupOf(sourceId: String): List<String> =
        query(
            "SELECT a.slug FROM event_artist ea JOIN artist a ON a.id = ea.artist_id JOIN event e ON e.id = ea.event_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { it.getString(1) }

    private fun tagsOf(sourceId: String): List<String> =
        query(
            "SELECT g.slug || ':' || egt.origin FROM event_genre_tag egt JOIN genre_tag g ON g.id = egt.genre_tag_id " +
                "JOIN event e ON e.id = egt.event_id WHERE e.source_id = '$sourceId' ORDER BY g.slug"
        ) { it.getString(1) }

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }
}
