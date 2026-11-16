package de.norm.events.genretag

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V257 against planted rows on a database migrated to before it. */
class DropAndFoldGenreCleanupBatch1MigrationTest {
    @Test
    fun `drops the non-genres, folds the spellings, renames where no canonical tag exists, and keeps the rest`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "256").migrate()
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
                        "INSERT INTO genre_tag (name, slug) VALUES ('Top40', 'top40'), ('Top 40', 'top-40'), " +
                            "('Rock''n Roll', 'rock-n-roll'), ('RocknRoll', 'rocknroll'), ('Dream-Pop', 'dream-pop'), " +
                            "('Musical', 'musical'), ('Filth', 'filth'), ('Future Beats', 'future-beats')"
                    )
                    statement.execute(
                        "INSERT INTO event_genre_tag (event_id, genre_tag_id) " +
                            "SELECT e.id, g.id FROM event e, genre_tag g WHERE (e.slug, g.slug) IN " +
                            "(('e1', 'top40'), ('e1', 'top-40'), ('e2', 'top-40'), ('e2', 'rocknroll'), ('e2', 'musical'), " +
                            "('e3', 'dream-pop'), ('e3', 'filth'), ('e3', 'future-beats'))"
                    )
                }
                flyway(postgres, "257").migrate()

                rows(connection, "SELECT slug || ':' || name || ':' || coalesce(family, '-') FROM events.genre_tag ORDER BY slug") {
                    it.getString(1)
                } shouldContainExactly
                    listOf("dreampop:Dreampop:pop", "future-beats:Future Beats:-", "rock-n-roll:Rock'n'Roll:-", "top40:Top40:-")
                rows(
                    connection,
                    "SELECT e.slug || ':' || g.slug FROM events.event_genre_tag egt JOIN events.event e ON e.id = egt.event_id " +
                        "JOIN events.genre_tag g ON g.id = egt.genre_tag_id ORDER BY 1"
                ) { it.getString(1) } shouldContainExactly
                    listOf("e1:top40", "e2:rock-n-roll", "e2:top40", "e3:dreampop", "e3:future-beats")
            }
        }
    }
}
