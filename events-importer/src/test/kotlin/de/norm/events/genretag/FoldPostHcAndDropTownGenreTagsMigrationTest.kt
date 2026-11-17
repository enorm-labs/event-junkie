package de.norm.events.genretag

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V261 against planted rows on a database migrated to before it. */
class FoldPostHcAndDropTownGenreTagsMigrationTest {
    @Test
    fun `moves the Post-HC links onto Post Hardcore and drops the two towns`() {
        migrated(withPostHardcore = true) { connection ->
            tags(connection) shouldContainExactly listOf("post-hardcore:Post Hardcore", "punk:Punk")
            links(connection) shouldContainExactly listOf("e1:post-hardcore", "e2:post-hardcore", "e3:punk")
        }
    }

    @Test
    fun `renames Post-HC where no Post Hardcore tag exists`() {
        migrated(withPostHardcore = false) { connection ->
            tags(connection) shouldContainExactly listOf("post-hardcore:Post Hardcore", "punk:Punk")
            links(connection) shouldContainExactly listOf("e1:post-hardcore", "e3:punk")
        }
    }

    private fun migrated(
        withPostHardcore: Boolean,
        check: (java.sql.Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "260").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('V', 'v')")
                    statement.execute(
                        "INSERT INTO event (title, slug, event_date, venue_id, source_url, source_id, event_type) " +
                            "SELECT 'E' || n, 'e' || n, DATE '2026-12-01', (SELECT id FROM venue), 'https://example.de/' || n, 'v:' || n, " +
                            "'CONCERT' FROM generate_series(1, 3) n"
                    )
                    statement.execute(
                        "INSERT INTO genre_tag (name, slug) VALUES ('Post-HC', 'post-hc'), ('Dresden', 'dresden'), " +
                            "('Neuruppin', 'neuruppin'), ('Punk', 'punk')"
                    )
                    if (withPostHardcore) statement.execute("INSERT INTO genre_tag (name, slug) VALUES ('Post Hardcore', 'post-hardcore')")
                    statement.execute(
                        "INSERT INTO event_genre_tag (event_id, genre_tag_id) " +
                            "SELECT e.id, g.id FROM event e, genre_tag g WHERE (e.slug, g.slug) IN " +
                            "(('e1', 'post-hc'), ('e2', 'post-hardcore'), ('e3', 'punk'), ('e3', 'dresden'), ('e3', 'neuruppin'))"
                    )
                }
                flyway(postgres, "261").migrate()
                check(connection)
            }
        }
    }

    private fun tags(connection: java.sql.Connection): List<String> =
        rows(connection, "SELECT slug || ':' || name FROM events.genre_tag ORDER BY slug") { it.getString(1) }

    private fun links(connection: java.sql.Connection): List<String> =
        rows(
            connection,
            "SELECT e.slug || ':' || g.slug FROM events.event_genre_tag egt JOIN events.event e ON e.id = egt.event_id " +
                "JOIN events.genre_tag g ON g.id = egt.genre_tag_id ORDER BY 1"
        ) { it.getString(1) }
}
