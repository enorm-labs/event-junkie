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

/** Runs V217 against planted rows on a database migrated to before it. */
class SplitCoBilledPromotersMigrationTest {
    @Test
    fun `every fused row splits as the importer now splits it, into the canonical names under their slugs`() {
        val rows = SPLIT_ROW.findAll(Files.readString(MIGRATION)).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
        rows.size shouldBe 8
        rows.forEach { (_, slug, name) ->
            canonicalPromoterName(name) shouldBe name
            SlugGenerator.slugify(name) shouldBe slug
        }
        val stored = rows.map { it.second }.toSet()
        FUSED.forEach { (fused, name) ->
            SlugGenerator.slugify(canonicalPromoterName(name)) shouldBe fused
            splitCoBilledPromoter(name) { it in stored }.map { SlugGenerator.slugify(canonicalPromoterName(it)) } shouldContainExactlyInAnyOrder
                rows.filter { it.first == fused }.map { it.second }
        }
    }

    @Test
    fun `credits each promoter on the fused row's events and drops the row`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "216").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Privatclub', 'privatclub')")
                    statement.execute(
                        "INSERT INTO promoter (name, slug) VALUES ('Milchmädchen Musikkultur GmbH & Dasmituns', " +
                            "'milchmadchen-musikkultur-gmbh-dasmituns'), ('Milchmädchen Musikkultur', 'milchmadchen-musikkultur')"
                    )
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT id, 'Indie Night', 'indie-night', DATE '2026-12-31', 'privatclub:1' FROM venue"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p " +
                            "WHERE p.slug = 'milchmadchen-musikkultur-gmbh-dasmituns'"
                    )
                }
                flyway(postgres, "217").migrate()

                credits(connection) shouldContainExactlyInAnyOrder listOf("milchmadchen-musikkultur", "dasmituns")
                rows(connection, "SELECT slug FROM events.promoter") { it.getString(1) } shouldContainExactlyInAnyOrder
                    listOf("milchmadchen-musikkultur", "dasmituns")
            }
        }
    }

    private fun credits(connection: Connection): List<String> =
        rows(connection, "SELECT p.slug FROM events.event_promoter ep JOIN events.promoter p ON p.id = ep.promoter_id") { it.getString(1) }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V217__split_co_billed_promoters.sql")

        /** One `(fused, slug, name)` row of the split list. */
        val SPLIT_ROW = Regex("""\('([^']+)',\s*'([^']+)',\s*'([^']+)'\)""")

        /** The fused rows on production, by slug and stored name. */
        val FUSED =
            mapOf(
                "milchmadchen-musikkultur-gmbh-dasmituns" to "Milchmädchen Musikkultur GmbH & Dasmituns",
                "concertburo-zahlmann-cocomico-theater" to "Concertbüro Zahlmann & Cocomico Theater",
                "crunch-tapes-positive-transmitter" to "Crunch Tapes & Positive Transmitter",
                "atok-alkh" to "Atok & Alkh"
            )
    }
}
