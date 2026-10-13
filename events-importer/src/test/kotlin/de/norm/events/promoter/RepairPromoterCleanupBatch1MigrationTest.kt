package de.norm.events.promoter

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V151 against planted rows on a database migrated to before it. */
class RepairPromoterCleanupBatch1MigrationTest {
    @Test
    fun `every survivor slug is the one the importer derives from its name`() {
        val rows = MERGE_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 5
        rows.forEach { (slug, name) -> SlugGenerator.slugify(canonicalPromoterName(name)) shouldBe slug }
    }

    @Test
    fun `merges the twins, unlinks the non-promoters and the venue on its own nights`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "150").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Badehaus', 'badehaus'), ('SO36', 'so36')")
                    for ((name, slug) in PROMOTERS) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    plant(statement, "badehaus", "b1", "badehaus", "d2mberlin", "fandom-night")
                    plant(statement, "badehaus", "b2", "d2m-berlin", "d2mberlin", "crunchtapes")
                    // The venue's name crediting a night at another venue is a promoter.
                    plant(statement, "so36", "s1", "badehaus")
                }
                flyway(postgres, "151").migrate()

                credits(connection, "b1") shouldBe listOf("d2m-berlin")
                credits(connection, "b2") shouldBe listOf("crunch-tapes", "d2m-berlin")
                credits(connection, "s1") shouldBe listOf("badehaus")
                slugs(connection) shouldContainExactlyInAnyOrder listOf("badehaus", "d2m-berlin", "crunch-tapes", "fandom-night")
                name(connection, "crunch-tapes") shouldBe "Crunch Tapes"
            }
        }
    }

    private fun plant(
        statement: Statement,
        venueSlug: String,
        sourceId: String,
        vararg promoterSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '$sourceId', DATE '2026-09-26', '$sourceId' FROM venue WHERE slug = '$venueSlug'"
        )
        for (slug in promoterSlugs) {
            statement.execute(
                "INSERT INTO event_promoter (event_id, promoter_id) " +
                    "SELECT e.id, p.id FROM event e, promoter p WHERE e.source_id = '$sourceId' AND p.slug = '$slug'"
            )
        }
    }

    private fun flyway(
        postgres: PostgreSQLContainer,
        target: String
    ): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun credits(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT p.slug FROM events.event_promoter ep JOIN events.event e ON e.id = ep.event_id " +
                "JOIN events.promoter p ON p.id = ep.promoter_id WHERE e.source_id = '$sourceId' ORDER BY p.slug"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.promoter") { it.getString(1) }

    private fun name(
        connection: Connection,
        slug: String
    ): String = rows(connection, "SELECT name FROM events.promoter WHERE slug = '$slug'") { it.getString(1) }.single()

    private fun <T : Any> rows(
        connection: Connection,
        sql: String,
        read: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V151__repair_promoter_cleanup_batch_1.sql")

        /** One `(loser, survivor, survivor_name)` row of the merge list. */
        val MERGE_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'([^']+)'\)""")

        val PROMOTERS =
            listOf(
                "Badehaus" to "badehaus",
                "d2mberlin" to "d2mberlin",
                "d2m berlin" to "d2m-berlin",
                "crunchtapes" to "crunchtapes",
                "Fandom Night" to "fandom-night"
            )
    }
}
