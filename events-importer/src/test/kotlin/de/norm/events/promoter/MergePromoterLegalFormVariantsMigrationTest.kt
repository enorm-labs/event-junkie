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

/** Runs V198 against planted rows on a database migrated to before it. */
class MergePromoterLegalFormVariantsMigrationTest {
    @Test
    fun `every survivor slug is the one the importer derives from its name`() {
        val rows = MERGE_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 5
        rows.forEach { (slug, name) -> SlugGenerator.slugify(canonicalPromoterName(name)) shouldBe slug }
    }

    @Test
    fun `merges into an existing survivor, renames where there is none, and fixes the capitals`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "197").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Tempodrom', 'tempodrom')")
                    for ((name, slug) in PROMOTERS) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    plant(statement, "tempodrom", "t1", "bossa-fm-veranstaltungsservice")
                    // An event that credits both rows keeps one link.
                    plant(statement, "tempodrom", "t2", "bossa-fm", "bossa-fm-veranstaltungsservice")
                    plant(statement, "tempodrom", "t3", "konzertburo-schonberg")
                    plant(statement, "tempodrom", "t4", "reset-production-e-k", "rundfunk-orchester-und-chore-ggmbh-berlin")
                    plant(statement, "tempodrom", "t5", "nxp-veranstaltungsbetriebs")
                }
                flyway(postgres, "198").migrate()

                credits(connection, "t1") shouldBe listOf("bossa-fm")
                credits(connection, "t2") shouldBe listOf("bossa-fm")
                credits(connection, "t3") shouldBe listOf("konzertburo-schoneberg")
                credits(connection, "t4") shouldBe listOf("reset-production", "rundfunk-orchester-und-chore")
                credits(connection, "t5") shouldBe listOf("nxp-veranstaltungsbetriebs")
                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf("bossa-fm", "konzertburo-schoneberg", "reset-production", "rundfunk-orchester-und-chore", "nxp-veranstaltungsbetriebs")
                name(connection, "rundfunk-orchester-und-chore") shouldBe "Rundfunk-Orchester und -Chöre"
                name(connection, "nxp-veranstaltungsbetriebs") shouldBe "NXP Veranstaltungsbetriebs"
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
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V198__merge_promoter_legal_form_variants.sql")

        /** One `(loser, survivor, survivor_name)` row of the merge list. */
        val MERGE_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'([^']+)'\)""")

        val PROMOTERS =
            listOf(
                "Bossa FM" to "bossa-fm",
                "Bossa FM Veranstaltungsservice" to "bossa-fm-veranstaltungsservice",
                "Konzertbüro Schoneberg" to "konzertburo-schoneberg",
                "Konzertbüro Schonberg" to "konzertburo-schonberg",
                "Reset Production e.K." to "reset-production-e-k",
                "Rundfunk-Orchester und -Chöre gGmbH Berlin" to "rundfunk-orchester-und-chore-ggmbh-berlin",
                "Nxp Veranstaltungsbetriebs" to "nxp-veranstaltungsbetriebs"
            )
    }
}
