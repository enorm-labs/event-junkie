package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V241 against planted rows on a database migrated to before it. */
class RestoreSoulEventsPromoterMigrationTest {
    @Test
    fun `renames the row in place and keeps its id when no survivor exists`() {
        migrated(SOUL) { connection, plantedIds ->
            promoters(connection) shouldContainExactly listOf("soul-events" to "Soul Events")
            promoterId(connection, "soul-events") shouldBe plantedIds.getValue("soul")
            SlugGenerator.slugify("Soul Events") shouldBe "soul-events"
            credits(connection, "s:2") shouldContainExactly listOf("soul-events")
        }
    }

    @Test
    fun `moves the links to an existing survivor and drops the old row`() {
        migrated(SOUL_EVENTS, SOUL) { connection, _ ->
            promoters(connection) shouldContainExactly listOf("soul-events" to "Soul Events")
            credits(connection, "s:1") shouldContainExactly listOf("soul-events")
            credits(connection, "s:2") shouldContainExactly listOf("soul-events")
        }
    }

    @Test
    fun `leaves a row a person renamed since alone`() {
        migrated("Soul Collective" to "soul") { connection, _ ->
            promoters(connection) shouldContainExactly listOf("soul" to "Soul Collective")
            credits(connection, "s:2") shouldContainExactly listOf("soul")
        }
    }

    @Test
    fun `is a no-op when the row is absent`() {
        migrated(SOUL_EVENTS, "Soulgigs" to "soulgigs") { connection, _ ->
            promoters(connection) shouldContainExactly listOf("soul-events" to "Soul Events", "soulgigs" to "Soulgigs")
            credits(connection, "s:1") shouldContainExactly listOf("soul-events")
            credits(connection, "s:2").shouldBeEmpty()
        }
    }

    /**
     * Plants [promoters] and two Admiralspalast events, credited `s:1` to `soul-events` and `s:2` to `soul` where that
     * row exists, on a database migrated to V240. Then migrates V241 and hands [check] the connection and the planted
     * promoters' ids by slug.
     */
    private fun migrated(
        vararg promoters: Pair<String, String>,
        check: (Connection, Map<String, Long>) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "240").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Admiralspalast', 'admiralspalast')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT v.id, 'Show ' || n, '2027-04-2' || n || '-admiralspalast-show-' || n, " +
                            "DATE '2027-04-20' + n, 's:' || n FROM venue v, generate_series(1, 2) AS n"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e " +
                            "JOIN (VALUES ('s:1', 'soul-events'), ('s:2', 'soul')) AS c(src, slug) ON c.src = e.source_id " +
                            "JOIN promoter p ON p.slug = c.slug"
                    )
                }
                val plantedIds = rows(connection, "SELECT slug, id FROM events.promoter") { it.getString(1) to it.getLong(2) }.toMap()
                flyway(postgres, "241").migrate()
                check(connection, plantedIds)
            }
        }
    }

    private fun promoters(connection: Connection): List<Pair<String, String>> =
        rows(connection, "SELECT slug, name FROM events.promoter ORDER BY slug") { it.getString(1) to it.getString(2) }

    private fun promoterId(
        connection: Connection,
        slug: String
    ): Long = rows(connection, "SELECT id FROM events.promoter WHERE slug = '$slug'") { it.getLong(1) }.single()

    private fun credits(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT p.slug FROM events.event_promoter ep JOIN events.event e ON e.id = ep.event_id " +
                "JOIN events.promoter p ON p.id = ep.promoter_id WHERE e.source_id = '$sourceId' ORDER BY p.slug"
        ) { it.getString(1) }

    private companion object {
        val SOUL = "Soul" to "soul"
        val SOUL_EVENTS = "Soul Events" to "soul-events"
    }
}
