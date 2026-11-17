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

/** Runs V262 against planted rows on a database migrated to before it. */
class MergeFrontstageAndMessedUpMagazinPromotersMigrationTest {
    @Test
    fun `moves the short credits' events and followers onto the two magazines and deletes the short rows`() {
        migrated(
            FRONTSTAGE_MAGAZINE,
            FRONTSTAGE,
            MESSED_UP_MAGAZINE,
            MESSED_UP_MAGAZIN,
            TRINITY,
            followed = listOf("frontstage", "messed-up-magazin")
        ) { connection ->
            promoters(connection) shouldContainExactly
                listOf(
                    "frontstage-magazine" to "Frontstage Magazine",
                    "messed-up-magazine" to "Messed!Up Magazine",
                    "trinity-music" to "Trinity Music"
                )
            credits(connection, "b:1") shouldContainExactly listOf("frontstage-magazine", "messed-up-magazine", "trinity-music")
            credits(connection, "b:2") shouldContainExactly listOf("frontstage-magazine", "messed-up-magazine")
            follows(connection) shouldContainExactly listOf("frontstage-magazine", "messed-up-magazine")
        }
    }

    @Test
    fun `keeps a follower's own follow of the survivor and drops the loser's`() {
        migrated(FRONTSTAGE_MAGAZINE, FRONTSTAGE, followed = listOf("frontstage-magazine", "frontstage")) { connection ->
            promoters(connection) shouldContainExactly listOf("frontstage-magazine" to "Frontstage Magazine")
            follows(connection) shouldContainExactly listOf("frontstage-magazine")
        }
    }

    @Test
    fun `renames a short row in place when no survivor exists`() {
        migrated(MESSED_UP_MAGAZIN, followed = listOf("messed-up-magazin")) { connection ->
            promoters(connection) shouldContainExactly listOf("messed-up-magazine" to "Messed!Up Magazine")
            credits(connection, "b:1") shouldContainExactly listOf("messed-up-magazine")
            follows(connection) shouldContainExactly listOf("messed-up-magazine")
        }
    }

    @Test
    fun `is a no-op when the short rows are absent`() {
        migrated(FRONTSTAGE_MAGAZINE, MESSED_UP_MAGAZINE) { connection ->
            promoters(connection) shouldContainExactly
                listOf("frontstage-magazine" to "Frontstage Magazine", "messed-up-magazine" to "Messed!Up Magazine")
            credits(connection, "b:1").shouldBeEmpty()
            credits(connection, "b:2") shouldContainExactly listOf("frontstage-magazine", "messed-up-magazine")
        }
    }

    /**
     * Plants [promoters] and two Bi Nuu events: `b:1` credits `trinity-music`, `frontstage` and `messed-up-magazin`, `b:2`
     * all four magazine rows, each where the row exists. One account follows the [followed] slugs. Then migrates V262 and
     * hands [check] the connection.
     */
    private fun migrated(
        vararg promoters: Pair<String, String>,
        followed: List<String> = emptyList(),
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "261").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Bi Nuu', 'bi-nuu')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT v.id, 'Night ' || n, '2027-04-0' || n || '-bi-nuu-night-' || n, " +
                            "DATE '2027-04-01' + n, 'b:' || n FROM venue v, generate_series(1, 2) AS n"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e " +
                            "JOIN (VALUES ('b:1', 'trinity-music'), ('b:1', 'frontstage'), ('b:1', 'messed-up-magazin'), " +
                            "('b:2', 'frontstage-magazine'), ('b:2', 'frontstage'), ('b:2', 'messed-up-magazine'), " +
                            "('b:2', 'messed-up-magazin')) AS c(src, slug) ON c.src = e.source_id JOIN promoter p ON p.slug = c.slug"
                    )
                    if (followed.isNotEmpty()) {
                        statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('fan@example.de', 'de')")
                        statement.execute(
                            "INSERT INTO accounts.follow (account_id, kind, entity_id) SELECT ac.id, 'promoter', p.id " +
                                "FROM accounts.account ac, promoter p WHERE p.slug IN (${followed.joinToString { "'$it'" }})"
                        )
                    }
                }
                flyway(postgres, "262").migrate()
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
        val FRONTSTAGE_MAGAZINE = "Frontstage Magazine" to "frontstage-magazine"
        val FRONTSTAGE = "Frontstage" to "frontstage"
        val MESSED_UP_MAGAZINE = "Messed!Up Magazine" to "messed-up-magazine"
        val MESSED_UP_MAGAZIN = "Messed Up Magazin" to "messed-up-magazin"
        val TRINITY = "Trinity Music" to "trinity-music"
    }
}
