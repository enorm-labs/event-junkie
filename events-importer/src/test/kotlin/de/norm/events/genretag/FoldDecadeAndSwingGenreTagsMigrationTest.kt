package de.norm.events.genretag

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V242 against planted rows on a database migrated to before it. */
class FoldDecadeAndSwingGenreTagsMigrationTest {
    @Test
    fun `moves the links onto the canonical tags, renames where none exists, and restores DOSIS`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "241").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('V', 'v')")
                    statement.execute(
                        "INSERT INTO event (title, slug, event_date, venue_id, source_url, source_id, event_type) " +
                            "SELECT 'E' || n, 'e' || n, DATE '2026-12-01', (SELECT id FROM venue), 'https://example.de/' || n, 'v:' || n, " +
                            "'PARTY' " +
                            "FROM generate_series(1, 3) n"
                    )
                    statement.execute(
                        "INSERT INTO genre_tag (name, slug) VALUES ('2000s', '2000s'), ('2000er', '2000er'), ('Y2K', 'y2k'), " +
                            "('Swing Dance', 'swing-dance'), ('Christmas Iconics', 'christmas-iconics'), ('Techno', 'techno')"
                    )
                    statement.execute(
                        "INSERT INTO event_genre_tag (event_id, genre_tag_id) " +
                            "SELECT e.id, g.id FROM event e, genre_tag g WHERE (e.slug, g.slug) IN " +
                            "(('e1', '2000s'), ('e1', 'y2k'), ('e2', '2000er'), ('e2', 'christmas-iconics'), " +
                            "('e3', 'swing-dance'), ('e3', 'techno'))"
                    )
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Dosis', 'dosis'), ('Marmor', 'marmor')")
                }
                flyway(postgres, "242").migrate()

                rows(connection, "SELECT slug || ':' || name || ':' || coalesce(family, '-') FROM events.genre_tag ORDER BY slug") {
                    it.getString(1)
                } shouldContainExactly listOf("2000s:2000s:-", "swing:Swing:jazz-blues", "techno:Techno:-")
                rows(
                    connection,
                    "SELECT e.slug || ':' || g.slug FROM events.event_genre_tag egt JOIN events.event e ON e.id = egt.event_id " +
                        "JOIN events.genre_tag g ON g.id = egt.genre_tag_id ORDER BY 1"
                ) { it.getString(1) } shouldContainExactly listOf("e1:2000s", "e2:2000s", "e3:swing", "e3:techno")
                rows(connection, "SELECT name FROM events.artist ORDER BY slug") { it.getString(1) } shouldContainExactly
                    listOf("DOSIS", "Marmor")
            }
        }
    }
}
