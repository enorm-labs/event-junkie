package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V230 against planted rows on a database migrated to before it. */
class FoldTranenpalastManagementPromoterMigrationTest {
    @Test
    fun `leaves one promoter, tranenpalast, linked to both events`() {
        migrated(promoters = listOf("Tränenpalast" to "tranenpalast", "tRÄNENpALAST management" to "tranenpalast-management")) { connection, survivorId ->
            promoters(connection) shouldContainExactly listOf(Triple(survivorId, "tranenpalast", "Tränenpalast"))
            credits(connection, "t:1") shouldContainExactly listOf("tranenpalast")
            credits(connection, "a:1") shouldContainExactly listOf("tranenpalast")
        }
    }

    @Test
    fun `renames the loser in place when no survivor exists`() {
        migrated(promoters = listOf("tRÄNENpALAST management" to "tranenpalast-management")) { connection, loserId ->
            promoters(connection) shouldContainExactly listOf(Triple(loserId, "tranenpalast", "Tränenpalast"))
            credits(connection, "a:1") shouldContainExactly listOf("tranenpalast")
        }
    }

    @Test
    fun `is a no-op when the loser is absent`() {
        migrated(promoters = listOf("Tränenpalast" to "tranenpalast")) { connection, survivorId ->
            promoters(connection) shouldContainExactly listOf(Triple(survivorId, "tranenpalast", "Tränenpalast"))
            credits(connection, "t:1") shouldContainExactly listOf("tranenpalast")
            credits(connection, "a:1").shouldBeEmpty()
        }
    }

    @Test
    fun `leaves an unrelated promoter alone`() {
        migrated(promoters = listOf("Tränenpalast" to "tranenpalast", "Heesen Konzerte" to "heesen-konzerte")) { connection, _ ->
            promoters(connection).map { it.second }.sorted() shouldBe listOf("heesen-konzerte", "tranenpalast")
        }
    }

    /**
     * Plants Tempodrom's event `t:1` on `tranenpalast` and Admiralspalast's `a:1` on `tranenpalast-management`, each only where
     * that row exists, then hands [check] the id of the first planted promoter.
     */
    private fun migrated(
        promoters: List<Pair<String, String>>,
        check: (Connection, Long) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "229").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Tempodrom', 'tempodrom'), ('Admiralspalast', 'admiralspalast')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT v.id, t.title, t.slug, t.d, t.src " +
                            "FROM venue v JOIN (VALUES ('tempodrom', 'Missverstehen Sie mich richtig', " +
                            "'2026-11-30-tempodrom-missverstehen-sie-mich-richtig', DATE '2026-11-30', 't:1'), " +
                            "('admiralspalast', 'Missverstehen Sie mich richtig', " +
                            "'2027-05-12-admiralspalast-missverstehen-sie-mich-richtig', DATE '2027-05-12', 'a:1')) " +
                            "AS t(venue, title, slug, d, src) ON t.venue = v.slug"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p " +
                            "WHERE (p.slug = 'tranenpalast' AND e.source_id = 't:1') " +
                            "OR (p.slug = 'tranenpalast-management' AND e.source_id = 'a:1')"
                    )
                }
                val firstId =
                    rows(connection, "SELECT id FROM events.promoter WHERE slug = '${promoters.first().second}'") { it.getLong(1) }.single()
                flyway(postgres, "230").migrate()
                check(connection, firstId)
            }
        }
    }

    private fun promoters(connection: Connection): List<Triple<Long, String, String>> =
        rows(connection, "SELECT id, slug, name FROM events.promoter") { Triple(it.getLong(1), it.getString(2), it.getString(3)) }

    private fun credits(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT p.slug FROM events.event_promoter ep JOIN events.event e ON e.id = ep.event_id " +
                "JOIN events.promoter p ON p.id = ep.promoter_id WHERE e.source_id = '$sourceId' ORDER BY p.slug"
        ) { it.getString(1) }
}
