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

/** Runs V263 against planted rows on a database migrated to before it. */
class StripAnniversaryFromCosmicWalkersPromoterMigrationTest {
    @Test
    fun `renames the anniversary row in place when no Cosmic Walkers row exists`() {
        migrated(ANNIVERSARY, THE_MYSTIC_ROSE, followed = listOf("30-jahre-cosmic-walkers")) { connection ->
            promoters(connection) shouldContainExactly
                listOf("cosmic-walkers" to "Cosmic Walkers", "the-mystic-rose" to "The Mystic Rose")
            credits(connection, "k:1") shouldContainExactly listOf("cosmic-walkers", "the-mystic-rose")
            follows(connection) shouldContainExactly listOf("cosmic-walkers")
        }
    }

    @Test
    fun `merges the anniversary row's events and followers into an existing Cosmic Walkers row`() {
        migrated(ANNIVERSARY, COSMIC_WALKERS, THE_MYSTIC_ROSE, followed = listOf("30-jahre-cosmic-walkers")) { connection ->
            promoters(connection) shouldContainExactly
                listOf("cosmic-walkers" to "Cosmic Walkers", "the-mystic-rose" to "The Mystic Rose")
            credits(connection, "k:1") shouldContainExactly listOf("cosmic-walkers", "the-mystic-rose")
            credits(connection, "k:2") shouldContainExactly listOf("cosmic-walkers")
            follows(connection) shouldContainExactly listOf("cosmic-walkers")
        }
    }

    @Test
    fun `keeps a follower's own follow of Cosmic Walkers and drops the anniversary one`() {
        migrated(ANNIVERSARY, COSMIC_WALKERS, followed = listOf("30-jahre-cosmic-walkers", "cosmic-walkers")) { connection ->
            promoters(connection) shouldContainExactly listOf("cosmic-walkers" to "Cosmic Walkers")
            follows(connection) shouldContainExactly listOf("cosmic-walkers")
        }
    }

    @Test
    fun `is a no-op when the anniversary row is absent`() {
        migrated(COSMIC_WALKERS, THE_MYSTIC_ROSE) { connection ->
            promoters(connection) shouldContainExactly
                listOf("cosmic-walkers" to "Cosmic Walkers", "the-mystic-rose" to "The Mystic Rose")
            credits(connection, "k:1") shouldContainExactly listOf("the-mystic-rose")
            credits(connection, "k:2") shouldContainExactly listOf("cosmic-walkers")
            follows(connection).shouldBeEmpty()
        }
    }

    /**
     * Plants [promoters] and two KitKat nights: `k:1` credits `the-mystic-rose` and `30-jahre-cosmic-walkers`, `k:2` both
     * Cosmic Walkers rows, each where the row exists. One account follows the [followed] slugs. Then migrates V263 and hands
     * [check] the connection.
     */
    private fun migrated(
        vararg promoters: Pair<String, String>,
        followed: List<String> = emptyList(),
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                // V261, not V262: #5232 adds V262, and a target that does not exist yet fails. V262 touches other rows.
                flyway(postgres, "261").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('KitKatClub', 'kitkatclub')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT v.id, 'Night ' || n, '2027-04-0' || n || '-kitkatclub-night-' || n, " +
                            "DATE '2027-04-01' + n, 'k:' || n FROM venue v, generate_series(1, 2) AS n"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e " +
                            "JOIN (VALUES ('k:1', 'the-mystic-rose'), ('k:1', '30-jahre-cosmic-walkers'), " +
                            "('k:2', '30-jahre-cosmic-walkers'), ('k:2', 'cosmic-walkers')) AS c(src, slug) " +
                            "ON c.src = e.source_id JOIN promoter p ON p.slug = c.slug"
                    )
                    if (followed.isNotEmpty()) {
                        statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('fan@example.de', 'de')")
                        statement.execute(
                            "INSERT INTO accounts.follow (account_id, kind, entity_id) SELECT ac.id, 'promoter', p.id " +
                                "FROM accounts.account ac, promoter p WHERE p.slug IN (${followed.joinToString { "'$it'" }})"
                        )
                    }
                }
                flyway(postgres, "263").migrate()
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
        val ANNIVERSARY = "30 Jahre Cosmic Walkers" to "30-jahre-cosmic-walkers"
        val COSMIC_WALKERS = "Cosmic Walkers" to "cosmic-walkers"
        val THE_MYSTIC_ROSE = "The Mystic Rose" to "the-mystic-rose"
    }
}
