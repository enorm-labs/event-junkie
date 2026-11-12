package de.norm.events.slug

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V243 against planted rows on a database migrated to before it. */
class ReslugTypographicApostrophesMigrationTest {
    @Test
    fun `re-slugs artists, genre tags and upcoming events as SlugGenerator now does, and merges the twins`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "242").migrate()
                plant(connection)
                flyway(postgres, "243").migrate()

                assertSoftly {
                    assertArtists(connection)
                    assertGenreTags(connection)
                    assertEvents(connection)
                }
            }
        }
    }

    private fun assertArtists(connection: Connection) {
        rows(connection, "SELECT slug || ':' || name FROM events.artist ORDER BY slug COLLATE \"C\"") { it.getString(1) } shouldContainExactly
            listOf(
                "a-b-c:A’B C",
                "boppin-b:Boppin‘ B",
                "c-est-clerc:\u2060C‘est Clerc",
                "d-arcangelo:D’Arcangelo",
                "dj-r-n-p:DJ R'n'P",
                "im-home-band:I’m Home",
                "kaptn-s-soren:Käptn’s Søren",
                "king-orgasmus-one:King Orgasmus One",
                "myata:Мʼята",
                "rave-d-amor:Rave d’Amor",
                "sheev:Sheev"
            )
        rows(
            connection,
            "SELECT alias.slug || ':' || a.slug FROM events.artist_slug_alias alias JOIN events.artist a ON a.id = alias.artist_id " +
                "ORDER BY alias.slug COLLATE \"C\""
        ) { it.getString(1) } shouldContainExactly
            listOf(
                "a-bc:a-b-c",
                "ab-c:a-b-c",
                "cest-clerc:c-est-clerc",
                "darcangelo:d-arcangelo",
                "dj-rnp:dj-r-n-p",
                "kaptns-soren:kaptn-s-soren",
                "orgi-s-weihnachtsshow:king-orgasmus-one",
                "orgis-weihnachtsshow:king-orgasmus-one",
                "rave-damor:rave-d-amor",
                "stoner-s-fall-sheev:sheev",
                "stoners-fall-sheev:sheev"
            )
        rows(
            connection,
            "SELECT e.event_date - CURRENT_DATE || ':' || a.slug || ':' || ea.role FROM events.event_artist ea " +
                "JOIN events.event e ON e.id = ea.event_id JOIN events.artist a ON a.id = ea.artist_id ORDER BY e.event_date"
        ) { it.getString(1) } shouldContainExactly listOf("3:dj-r-n-p:SUPPORT", "4:dj-r-n-p:HEADLINER")
        rows(connection, "SELECT a.slug FROM accounts.follow f JOIN events.artist a ON a.id = f.entity_id") {
            it.getString(1)
        } shouldContainExactly listOf("dj-r-n-p")
        rows(connection, "SELECT name, slug FROM events.artist WHERE name ~ '[’‘]' AND slug <> 'im-home-band'") {
            it.getString(1) to it.getString(2)
        }.forEach { (name, slug) -> slug shouldBe SlugGenerator.slugify(name) }
    }

    private fun assertGenreTags(connection: Connection) {
        rows(connection, "SELECT slug FROM events.genre_tag ORDER BY slug COLLATE \"C\"") { it.getString(1) } shouldContainExactly
            listOf("50-s-heartbreakers", "rock-n-roll-cabaret")
        rows(
            connection,
            "SELECT g.slug FROM events.event_genre_tag egt JOIN events.genre_tag g ON g.id = egt.genre_tag_id ORDER BY g.slug COLLATE \"C\""
        ) { it.getString(1) } shouldContainExactly listOf("50-s-heartbreakers", "rock-n-roll-cabaret")
    }

    private fun assertEvents(connection: Connection) {
        rows(connection, "SELECT event_date - CURRENT_DATE || ':' || substr(slug, 12) FROM events.event ORDER BY event_date, slug COLLATE \"C\"") {
            it.getString(1)
        } shouldContainExactly
            listOf(
                "-10:soda-club-im-home",
                "-9:soda-club-im-home",
                "3:soda-club-i-m-home",
                "4:soda-club-i-m-home-2000",
                "5:soda-club-i-m-home",
                "5:soda-club-im-home",
                "6:soda-club-i-m-home",
                "7:soda-club-i-m-home-2"
            )
        rows(
            connection,
            "SELECT event_date - CURRENT_DATE || ':' || split_part(source_id, ':', 1) || ':' || " +
                "substr(source_id, length(split_part(source_id, ':', 1)) + 13) FROM events.event " +
                "WHERE source_id NOT LIKE 'soda:%' ORDER BY event_date"
        ) { it.getString(1) } shouldContainExactly listOf("-9:z_bar:im-home", "6:z_bar:i-m-home", "7:hole44:im-home")
    }

    private fun plant(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('DJ R''n''P', 'dj-r-n-p'), ('DJ R’n’P', 'dj-rnp'), " +
                    "('D’Arcangelo', 'darcangelo'), ('Rave d’Amor', 'rave-damor'), ('A’B C', 'ab-c'), ('A B’C', 'a-bc'), " +
                    "('Boppin‘ B', 'boppin-b'), ('I’m Home', 'im-home-band'), ('Мʼята', 'myata'), " +
                    "('Käptn’s Søren', 'kaptns-soren'), ('\u2060C‘est Clerc', 'cest-clerc'), " +
                    "('King Orgasmus One', 'king-orgasmus-one'), ('Sheev', 'sheev')"
            )
            statement.execute(
                "INSERT INTO artist_slug_alias (slug, artist_id) SELECT alias, (SELECT id FROM artist WHERE slug = holder) " +
                    "FROM (VALUES ('rave-d-amor', 'rave-damor'), ('orgis-weihnachtsshow', 'king-orgasmus-one'), " +
                    "('stoners-fall-sheev', 'sheev')) AS a (alias, holder)"
            )
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Soda Club', 'soda-club')")
            statement.execute(
                "INSERT INTO event (title, slug, event_date, venue_id, source_url, source_id, event_type) " +
                    "SELECT title, to_char(CURRENT_DATE + days, 'YYYY-MM-DD') || '-soda-club-' || tail, CURRENT_DATE + days, " +
                    "(SELECT id FROM venue), 'https://example.de/' || n, 'soda:' || n, 'PARTY' " +
                    "FROM (VALUES (1, 'I’m Home', -10, 'im-home'), (2, 'I’m Home', 3, 'im-home'), " +
                    "(3, 'I’m Home', 4, 'im-home-2000'), (4, 'I’m Home', 5, 'im-home'), (5, 'I''m Home', 5, 'i-m-home')) " +
                    "AS e (n, title, days, tail)"
            )
            statement.execute(
                "INSERT INTO event (title, slug, event_date, venue_id, source_url, source_id, event_type) " +
                    "SELECT 'I\u2019m Home', day || '-soda-club-im-home', CURRENT_DATE + days, (SELECT id FROM venue), " +
                    "'https://example.de/' || days, 'z_bar:' || day || '-im-home', 'PARTY' " +
                    "FROM (SELECT days, to_char(CURRENT_DATE + days, 'YYYY-MM-DD') AS day FROM (VALUES (-9), (6)) AS d (days)) AS e"
            )
            statement.execute(
                "INSERT INTO event (title, slug, event_date, venue_id, source_url, source_id, event_type) " +
                    "SELECT 'I\u2019m Home!', day || '-soda-club-im-home-2', CURRENT_DATE + 7, (SELECT id FROM venue), " +
                    "'https://example.de/7', 'hole44:' || day || '-im-home', 'PARTY' FROM (SELECT to_char(CURRENT_DATE + 7, 'YYYY-MM-DD') AS day) AS d"
            )
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role) " +
                    "SELECT e.id, a.id, role FROM (VALUES ('soda:2', 'dj-rnp', 'SUPPORT'), ('soda:3', 'dj-rnp', 'SUPPORT'), " +
                    "('soda:3', 'dj-r-n-p', 'HEADLINER')) AS link (source_id, slug, role) " +
                    "JOIN event e ON e.source_id = link.source_id JOIN artist a ON a.slug = link.slug"
            )
            statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('w-masked@example.de', 'en')")
            statement.execute(
                "INSERT INTO accounts.follow (account_id, kind, entity_id) " +
                    "SELECT (SELECT id FROM accounts.account), 'artist', id FROM artist WHERE slug = 'dj-rnp'"
            )
            statement.execute(
                "INSERT INTO genre_tag (name, slug) VALUES ('50’s Heartbreakers', '50s-heartbreakers'), " +
                    "('Rock‘n’Roll Cabaret', 'rocknroll-cabaret'), ('Rock''n''Roll Cabaret', 'rock-n-roll-cabaret')"
            )
            statement.execute(
                "INSERT INTO event_genre_tag (event_id, genre_tag_id) SELECT e.id, g.id FROM event e, genre_tag g " +
                    "WHERE e.source_id = 'soda:2' AND g.slug IN ('50s-heartbreakers', 'rocknroll-cabaret')"
            )
        }
    }
}
