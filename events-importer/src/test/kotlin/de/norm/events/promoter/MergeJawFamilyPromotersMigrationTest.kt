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

/** Runs V282 against planted rows on a database migrated to before it. */
class MergeJawFamilyPromotersMigrationTest {
    @Test
    fun `renames the dotted row in place when no Jaw Family row exists`() {
        migrated(DOTTED, OTHER_PROMOTER, followed = listOf("j-a-w-family")) { connection ->
            promoters(connection) shouldContainExactly
                listOf("another-promoter" to "Another Promoter", "jaw-family" to "Jaw Family")
            credits(connection, "k:1") shouldContainExactly listOf("another-promoter", "jaw-family")
            follows(connection) shouldContainExactly listOf("jaw-family")
        }
    }

    @Test
    fun `merges the dotted row's events and followers into an existing Jaw Family row`() {
        migrated(DOTTED, JAW_FAMILY, OTHER_PROMOTER, followed = listOf("j-a-w-family")) { connection ->
            promoters(connection) shouldContainExactly
                listOf("another-promoter" to "Another Promoter", "jaw-family" to "Jaw Family")
            credits(connection, "k:1") shouldContainExactly listOf("another-promoter", "jaw-family")
            credits(connection, "k:2") shouldContainExactly listOf("jaw-family")
            follows(connection) shouldContainExactly listOf("jaw-family")
        }
    }

    @Test
    fun `keeps a follower's own follow of Jaw Family and drops the dotted one`() {
        migrated(DOTTED, JAW_FAMILY, followed = listOf("j-a-w-family", "jaw-family")) { connection ->
            promoters(connection) shouldContainExactly listOf("jaw-family" to "Jaw Family")
            follows(connection) shouldContainExactly listOf("jaw-family")
        }
    }

    @Test
    fun `is a no-op when the dotted row is absent`() {
        migrated(JAW_FAMILY, OTHER_PROMOTER) { connection ->
            promoters(connection) shouldContainExactly
                listOf("another-promoter" to "Another Promoter", "jaw-family" to "Jaw Family")
            credits(connection, "k:1") shouldContainExactly listOf("another-promoter")
            credits(connection, "k:2") shouldContainExactly listOf("jaw-family")
            follows(connection).shouldBeEmpty()
        }
    }

    /**
     * Plants [promoters] and two Festsaal nights: `k:1` credits `another-promoter` and `j-a-w-family`, `k:2` both
     * Jaw Family rows, each where the row exists. One account follows the [followed] slugs. Then migrates V282 and hands
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
                flyway(postgres, "281").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Festsaal Kreuzberg', 'festsaal-kreuzberg')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT v.id, 'Night ' || n, '2027-04-0' || n || '-festsaal-kreuzberg-night-' || n, " +
                            "DATE '2027-04-01' + n, 'k:' || n FROM venue v, generate_series(1, 2) AS n"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e " +
                            "JOIN (VALUES ('k:1', 'another-promoter'), ('k:1', 'j-a-w-family'), " +
                            "('k:2', 'j-a-w-family'), ('k:2', 'jaw-family')) AS c(src, slug) " +
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
                flyway(postgres, "282").migrate()
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
        val DOTTED = "J.A.W. Family" to "j-a-w-family"
        val JAW_FAMILY = "Jaw Family" to "jaw-family"
        val OTHER_PROMOTER = "Another Promoter" to "another-promoter"
    }
}
