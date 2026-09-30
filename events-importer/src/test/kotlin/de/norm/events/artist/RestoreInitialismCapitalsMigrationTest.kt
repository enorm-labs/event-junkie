package de.norm.events.artist

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V068 (#2082) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreInitialismCapitalsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("67").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Uvb', 'uvb')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('A\$ap Rocky', 'a-ap-rocky')")
            // Corrected by hand since: the migration must not overwrite it.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Joe B.R.T.', 'joe-brt')")
            // A move onto a free slug, and a fold onto a row the next import already minted.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Deejay Lito Bolton', 'deejay-lito-bolton')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Deejay Tc', 'deejay-tc')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('DJ TC', 'dj-tc')")
            plantEvent(statement, "m1", "deejay-tc")
            plantEvent(statement, "m2", "deejay-tc", "dj-tc")
            plantEvent(statement, "m3", "deejay-lito-bolton")
        }
        flyway("68").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `restores the capitals of a row that still carries the minted name, and leaves one changed since`() {
        names()["uvb"] shouldBe "UVB"
        names()["a-ap-rocky"] shouldBe "A\$AP Rocky"
        names()["joe-brt"] shouldBe "Joe B.R.T."
    }

    @Test
    fun `moves a Deejay row onto its DJ slug, or folds it into the DJ row that exists`() {
        names()["dj-lito-bolton"] shouldBe "DJ Lito Bolton"
        eventsOf("dj-lito-bolton") shouldContainExactlyInAnyOrder listOf("m3")
        names().keys.filter { it.startsWith("deejay") }.shouldBeEmpty()
        eventsOf("dj-tc") shouldContainExactlyInAnyOrder listOf("m1", "m2")
    }

    @Test
    fun `every new slug is the slug of its new name`() {
        val migration = File("src/main/resources/db/migration/V068__restore_initialism_capitals_and_fold_deejay.sql").readText()
        ROW
            .findAll(migration)
            .map { it.destructured }
            .filter { (first, second, newName) -> SlugGenerator.slugify(newName) !in setOf(first, second) }
            .map { (first, _, newName) -> "'$first' is named '$newName'" }
            .toList()
            .shouldBeEmpty()
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', '$sourceId', DATE '2026-10-01', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        for (slug in artistSlugs) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) " +
                    "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun names(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name FROM events.artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap()
            }
        }

    private fun eventsOf(slug: String): List<String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT e.source_id FROM events.event e JOIN events.event_artist ea ON ea.event_id = e.id " +
                        "JOIN events.artist a ON a.id = ea.artist_id WHERE a.slug = '$slug'"
                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    private companion object {
        // A step-1 row is `(slug, old_name, new_name)`, a step-2 row `(old_slug, new_slug, new_name)`.
        val ROW = Regex("""\('([^']+)', '([^']+)', '([^']+)'\)""")
    }
}
