package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V228 against planted rows on a database migrated to before it. */
class RestoreBeyondBookingPromoterMigrationTest {
    @Test
    fun `the new slug is the one the importer derives from the credit`() {
        SlugGenerator.slugify(canonicalPromoterName("Beyond Booking")) shouldBe "beyond-booking"
    }

    @Test
    fun `renames the row in place, so its id and both past events stay`() {
        migrated(promoters = listOf("Beyond" to "beyond")) { connection, beforeId ->
            promoters(connection) shouldContainExactly listOf(Triple(beforeId, "beyond-booking", "Beyond Booking"))
            credits(connection, "k:1") shouldContainExactly listOf("beyond-booking")
            credits(connection, "k:2") shouldContainExactly listOf("beyond-booking")
        }
    }

    // An import on the fixed normalizer can mint the survivor before the migration reaches a database.
    @Test
    fun `merges into a survivor that already exists`() {
        migrated(promoters = listOf("Beyond" to "beyond", "Beyond Booking" to "beyond-booking")) { connection, _ ->
            promoters(connection).map { it.second } shouldContainExactly listOf("beyond-booking")
            credits(connection, "k:1") shouldContainExactly listOf("beyond-booking")
            credits(connection, "k:2") shouldContainExactly listOf("beyond-booking")
            credits(connection, "k:3") shouldContainExactly listOf("beyond-booking")
        }
    }

    private fun migrated(
        promoters: List<Pair<String, String>>,
        check: (Connection, Long) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "227").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Kantine am Berghain', 'kantine-am-berghain')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, t.title, t.slug, t.d, t.src " +
                            "FROM venue, (VALUES ('Zeug', '2026-10-30-kantine-am-berghain-zeug', DATE '2026-10-30', 'k:1'), " +
                            "('Night', '2026-11-05-kantine-am-berghain-night', DATE '2026-11-05', 'k:2'), " +
                            "('Later', '2026-11-20-kantine-am-berghain-later', DATE '2026-11-20', 'k:3')) AS t(title, slug, d, src)"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p " +
                            "WHERE (p.slug = 'beyond' AND e.source_id IN ('k:1', 'k:2')) " +
                            "OR (p.slug = 'beyond-booking' AND e.source_id IN ('k:2', 'k:3'))"
                    )
                }
                val beforeId = rows(connection, "SELECT id FROM events.promoter WHERE slug = 'beyond'") { it.getLong(1) }.single()
                flyway(postgres, "228").migrate()
                check(connection, beforeId)
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
