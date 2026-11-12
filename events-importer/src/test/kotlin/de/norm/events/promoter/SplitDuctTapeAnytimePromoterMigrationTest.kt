package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V246 against planted rows on a database migrated to before it. */
class SplitDuctTapeAnytimePromoterMigrationTest {
    @Test
    fun `deletes the joint promoter with its event link and its followers`() {
        migrated(DUCT_TAPE_ANYTIME, CRUNCH_TAPES) { connection ->
            promoters(connection) shouldContainExactly listOf("crunch-tapes")
            credits(connection) shouldContainExactly listOf("crunch-tapes")
            follows(connection) shouldContainExactly listOf("crunch-tapes")
        }
    }

    @Test
    fun `is a no-op when the joint promoter is absent`() {
        migrated(CRUNCH_TAPES) { connection ->
            promoters(connection) shouldContainExactly listOf("crunch-tapes")
            credits(connection) shouldContainExactly listOf("crunch-tapes")
            follows(connection) shouldContainExactly listOf("crunch-tapes")
        }
    }

    /**
     * Plants [promoters], one Privatclub event that credits each of them, and one account that follows each. Then migrates
     * V246 and hands [check] the connection.
     */
    private fun migrated(
        vararg promoters: Pair<String, String>,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "244").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Privatclub', 'privatclub')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT v.id, 'Cosmopolitan', " +
                            "'2026-11-28-privatclub-cosmopolitan', DATE '2026-11-28', 'p:1' FROM venue v"
                    )
                    statement.execute("INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p")
                    statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('fan@example.de', 'de')")
                    statement.execute(
                        "INSERT INTO accounts.follow (account_id, kind, entity_id) SELECT ac.id, 'promoter', p.id " +
                            "FROM accounts.account ac, promoter p"
                    )
                }
                flyway(postgres, "246").migrate()
                check(connection)
            }
        }
    }

    private fun promoters(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.promoter ORDER BY slug") { it.getString(1) }

    private fun credits(connection: Connection): List<String> =
        rows(
            connection,
            "SELECT p.slug FROM events.event_promoter ep JOIN events.promoter p ON p.id = ep.promoter_id ORDER BY p.slug"
        ) { it.getString(1) }

    private fun follows(connection: Connection): List<String> =
        rows(
            connection,
            "SELECT COALESCE(p.slug, '<gone>') FROM accounts.follow f LEFT JOIN events.promoter p ON p.id = f.entity_id " +
                "WHERE f.kind = 'promoter' ORDER BY 1"
        ) { it.getString(1) }

    private companion object {
        val DUCT_TAPE_ANYTIME = "Duct Tape × Anytime" to "duct-tape-anytime"
        val CRUNCH_TAPES = "Crunch Tapes" to "crunch-tapes"
    }
}
