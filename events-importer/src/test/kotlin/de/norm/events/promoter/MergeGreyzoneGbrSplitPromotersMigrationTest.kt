package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V245 against planted rows on a database migrated to before it. */
class MergeGreyzoneGbrSplitPromotersMigrationTest {
    @Test
    fun `moves the split parts' events and followers onto Greyzone Concerts and deletes the parts`() {
        migrated(GREYZONE, GREY, PROMOTION_GREY, VON_BRONIKOWSKI, followed = listOf("grey", "von-bronikowski")) { connection ->
            promoters(connection) shouldContainExactly listOf("greyzone-concerts" to "Greyzone Concerts")
            credits(connection, "c:1") shouldContainExactly listOf("greyzone-concerts")
            credits(connection, "c:2") shouldContainExactly listOf("greyzone-concerts")
            credits(connection, "c:3") shouldContainExactly listOf("greyzone-concerts")
            follows(connection) shouldContainExactly listOf("greyzone-concerts")
        }
    }

    @Test
    fun `keeps a follower's own follow of the survivor and drops the loser's`() {
        migrated(GREYZONE, GREY, followed = listOf("greyzone-concerts", "grey")) { connection ->
            promoters(connection) shouldContainExactly listOf("greyzone-concerts" to "Greyzone Concerts")
            follows(connection) shouldContainExactly listOf("greyzone-concerts")
        }
    }

    @Test
    fun `renames a part in place when no survivor exists`() {
        migrated(GREY, VON_BRONIKOWSKI, followed = listOf("grey")) { connection ->
            promoters(connection) shouldContainExactly listOf("greyzone-concerts" to "Greyzone Concerts")
            credits(connection, "c:2") shouldContainExactly listOf("greyzone-concerts")
            credits(connection, "c:3") shouldContainExactly listOf("greyzone-concerts")
            follows(connection) shouldContainExactly listOf("greyzone-concerts")
        }
    }

    @Test
    fun `is a no-op when the parts are absent`() {
        migrated(GREYZONE, "Crunch Tapes" to "crunch-tapes") { connection ->
            promoters(connection) shouldContainExactly
                listOf("crunch-tapes" to "Crunch Tapes", "greyzone-concerts" to "Greyzone Concerts")
            credits(connection, "c:1") shouldContainExactly listOf("greyzone-concerts")
            credits(connection, "c:2").shouldBeEmpty()
        }
    }

    /**
     * Plants [promoters] and three Columbiahalle events: `c:1` credits `greyzone-concerts`, `promotion-grey` and
     * `von-bronikowski`, `c:2` `promotion-grey` and `von-bronikowski`, `c:3` `grey` and `von-bronikowski`, each where the row
     * exists. One account follows the [followed] slugs. Then migrates V245 and hands [check] the connection.
     */
    private fun migrated(
        vararg promoters: Pair<String, String>,
        followed: List<String> = emptyList(),
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "244").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Columbiahalle', 'columbiahalle')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT v.id, 'Night ' || n, '2026-12-0' || n || '-columbiahalle-night-' || n, " +
                            "DATE '2026-12-01' + n, 'c:' || n FROM venue v, generate_series(1, 3) AS n"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e " +
                            "JOIN (VALUES ('c:1', 'greyzone-concerts'), ('c:1', 'promotion-grey'), ('c:1', 'von-bronikowski'), " +
                            "('c:2', 'promotion-grey'), ('c:2', 'von-bronikowski'), ('c:3', 'grey'), ('c:3', 'von-bronikowski')) " +
                            "AS c(src, slug) ON c.src = e.source_id JOIN promoter p ON p.slug = c.slug"
                    )
                    if (followed.isNotEmpty()) {
                        statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('fan@example.de', 'de')")
                        statement.execute(
                            "INSERT INTO accounts.follow (account_id, kind, entity_id) SELECT ac.id, 'promoter', p.id " +
                                "FROM accounts.account ac, promoter p WHERE p.slug IN (${followed.joinToString { "'$it'" }})"
                        )
                    }
                }
                flyway(postgres, "245").migrate()
                check(connection)
            }
        }
    }

    private fun promoters(connection: Connection): List<Pair<String, String>> =
        rows(connection, "SELECT slug, name FROM events.promoter ORDER BY slug") { it.getString(1) to it.getString(2) }

    private fun credits(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT p.slug FROM events.event_promoter ep JOIN events.event e ON e.id = ep.event_id " +
                "JOIN events.promoter p ON p.id = ep.promoter_id WHERE e.source_id = '$sourceId' ORDER BY p.slug"
        ) { it.getString(1) }

    private fun follows(connection: Connection): List<String> =
        rows(
            connection,
            "SELECT COALESCE(p.slug, '<gone>') FROM accounts.follow f LEFT JOIN events.promoter p ON p.id = f.entity_id " +
                "WHERE f.kind = 'promoter' ORDER BY 1"
        ) { it.getString(1) }

    private companion object {
        val GREYZONE = "Greyzone Concerts" to "greyzone-concerts"
        val GREY = "Grey" to "grey"
        val PROMOTION_GREY = "Promotion Grey" to "promotion-grey"
        val VON_BRONIKOWSKI = "von Bronikowski" to "von-bronikowski"
    }
}
