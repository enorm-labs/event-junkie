package de.norm.events.artist

import de.norm.events.flyway
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V196 (#3938) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropSisyphosKonzertMarkerArtistsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "195").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for ((name, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
            plant(statement, "sisyphos:2026-10-16", "suboptimal-dub-konzert", "pavelo-schnell-konzert")
            plant(statement, "sisyphos:2026-10-23", "matze-sampler-konzert", "tasheeno-konzert", "micha-stahl")
        }
        flyway(postgres, "196").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `every slug in the migration is the one the importer derives from the stored name`() {
        val slugs = DROP.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] }.toList()
        slugs shouldBe listOf("suboptimal-dub-konzert", "pavelo-schnell-konzert", "matze-sampler-konzert", "tasheeno-konzert")
        artistSlugFor("Suboptimal Dub *Konzert") shouldBe "suboptimal-dub-konzert"
        artistSlugFor("Pavelo & Schnell *Konzert") shouldBe "pavelo-schnell-konzert"
        artistSlugFor("Matze Sampler *KONZERT") shouldBe "matze-sampler-konzert"
        artistSlugFor("Tasheeno *Konzert") shouldBe "tasheeno-konzert"
    }

    @Test
    fun `drops every marked act, and keeps an act billed beside them`() {
        slugs() shouldContainExactlyInAnyOrder listOf("micha-stahl")
    }

    private fun plant(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-23', '$sourceId' FROM venue"
        )
        for (slug in artistSlugs) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) " +
                    "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun slugs(): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug FROM events.artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V196__drop_sisyphos_konzert_marker_artists.sql")
        val DROP = Regex("""drop_artist_billed_only_by\('([^']+)', 'sisyphos:'\)""")
        val ARTISTS =
            listOf(
                "Suboptimal Dub *Konzert" to "suboptimal-dub-konzert",
                "Pavelo & Schnell *Konzert" to "pavelo-schnell-konzert",
                "Matze Sampler *KONZERT" to "matze-sampler-konzert",
                "Tasheeno *Konzert" to "tasheeno-konzert",
                "Micha Stahl" to "micha-stahl"
            )
    }
}
