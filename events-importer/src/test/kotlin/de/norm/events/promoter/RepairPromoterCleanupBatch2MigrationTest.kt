package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V199 against planted rows on a database migrated to before it. */
class RepairPromoterCleanupBatch2MigrationTest {
    @Test
    fun `every survivor and part slug is the one the importer derives from its name`() {
        val rows = MERGE_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 16
        rows.forEach { (slug, name) -> SlugGenerator.slugify(canonicalPromoterName(name)) shouldBe slug }
    }

    @Test
    fun `merges, renames, unlinks and splits the rows the second cleanup judged`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "198").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('SO36', 'so36-venue')")
                    for ((name, slug) in PROMOTERS) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    plant(statement, "so36-venue", "e1", "mb", "berlin")
                    // An event that credits both rows keeps one link.
                    plant(statement, "so36-venue", "e2", "bossa", "bossa-fm")
                    plant(statement, "so36-venue", "e3", "capital", "como-esta-wally-producciones-y-management-sl", "so36")
                    plant(statement, "so36-venue", "e4", "girls-town-party", "atr-party-berlin", "new-berlin-konzerte")
                    plant(statement, "so36-venue", "e5", "now", "ever")
                    // One part exists already, the other is created.
                    plant(statement, "so36-venue", "e6", "drift-puschen", "drift")
                    plant(statement, "so36-venue", "e7", "afro-haus-purple")
                }
                flyway(postgres, "199").migrate()

                credits(connection, "e1") shouldBe listOf("mb-konzerte", "new-berlin-konzerte")
                credits(connection, "e2") shouldBe listOf("bossa-fm")
                credits(connection, "e3") shouldBe listOf("capital-music", "como-esta-wally", "so36")
                credits(connection, "e4") shouldBe listOf("new-berlin-konzerte")
                credits(connection, "e5") shouldBe listOf("now-and-ever")
                credits(connection, "e6") shouldBe listOf("drift", "puschen")
                credits(connection, "e7") shouldBe listOf("afro-haus", "purple-events")
                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf(
                        "mb-konzerte",
                        "new-berlin-konzerte",
                        "bossa-fm",
                        "capital-music",
                        "como-esta-wally",
                        "so36",
                        "girls-town-party",
                        "atr-party-berlin",
                        "now-and-ever",
                        "drift",
                        "puschen",
                        "afro-haus",
                        "purple-events"
                    )
                name(connection, "so36") shouldBe "SO36"
                name(connection, "capital-music") shouldBe "Capital Music"
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

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V199__repair_promoter_cleanup_batch_2.sql")

        /** One `(loser, survivor, survivor_name)` row of the merge list, or one `(loser, part, part_name)` of the split list. */
        val MERGE_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'([^']+)'\)""")

        val PROMOTERS =
            listOf(
                "MB –" to "mb",
                "MB Konzerte" to "mb-konzerte",
                "Berlin" to "berlin",
                "New Berlin Konzerte" to "new-berlin-konzerte",
                "Bossa" to "bossa",
                "Bossa FM" to "bossa-fm",
                "Capital" to "capital",
                "Como Esta Wally Producciones Y Management Sl" to "como-esta-wally-producciones-y-management-sl",
                "so36" to "so36",
                "Girls Town Party" to "girls-town-party",
                "Atr Party Berlin" to "atr-party-berlin",
                "Now" to "now",
                "Ever" to "ever",
                "Now And Ever" to "now-and-ever",
                "Drift & Puschen" to "drift-puschen",
                "Drift" to "drift",
                "Afro Haus & Purple" to "afro-haus-purple"
            )
    }
}
