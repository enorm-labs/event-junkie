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

/**
 * Runs V045 (#1761) against planted artist rows on a database migrated to just before it: a merge
 * onto an existing slug, a rename, a rename whose slug is taken, and an unlinked bracketed leftover.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StripAnnotationBracketsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("44").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            // A merge: the survivor exists, and one event bills both sides.
            plantArtist(statement, "Paula Paula (Zusatzshow)", "paula-paula-zusatzshow")
            plantArtist(statement, "Paula Paula", "paula-paula")
            plantEvent(statement, "p1", "paula-paula-zusatzshow")
            plantEvent(statement, "p2", "paula-paula-zusatzshow", "paula-paula")
            // A rename onto a free slug, with a MusicBrainz verdict taken under the bracketed name.
            plantArtist(statement, "Flow Rea (Est)", "flow-rea-est")
            statement.execute("UPDATE artist SET musicbrainz_match = 'NONE', musicbrainz_checked_at = now() WHERE slug = 'flow-rea-est'")
            plantEvent(statement, "f1", "flow-rea-est")
            // A rename whose slug another act already holds: no evidence they are one act.
            plantArtist(statement, "goat (jp)", "goat-jp")
            plantArtist(statement, "Goat", "goat")
            plantEvent(statement, "g1", "goat-jp")
            plantEvent(statement, "g2", "goat")
            // A bracket that is the name, billed.
            plantArtist(statement, "All(h)ours", "all-h-ours")
            plantEvent(statement, "a1", "all-h-ours")
            // Unlinked rows: a bracketed leftover goes, a plain one stays.
            plantArtist(statement, "Fracture (Uk)", "fracture-uk")
            plantArtist(statement, "Plain", "plain")
        }
        flyway("45").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `moves the loser's billing onto the existing survivor and deletes the loser`() {
        artists().containsKey("paula-paula-zusatzshow") shouldBe false
        eventsOf("paula-paula") shouldContainExactlyInAnyOrder listOf("p1", "p2")
    }

    @Test
    fun `renames onto a free slug and queues the row for MusicBrainz again`() {
        artists()["flow-rea"] shouldBe "Flow Rea"
        eventsOf("flow-rea") shouldContainExactlyInAnyOrder listOf("f1")
        query("SELECT musicbrainz_match, musicbrainz_checked_at FROM artist WHERE slug = 'flow-rea'") { rs ->
            rs.getString(1) to rs.getTimestamp(2)
        }.single() shouldBe ("UNCHECKED" to null)
    }

    @Test
    fun `keeps a bracket whose stripped slug another act holds`() {
        artists()["goat-jp"] shouldBe "goat (jp)"
        eventsOf("goat") shouldContainExactlyInAnyOrder listOf("g2")
    }

    @Test
    fun `leaves a bracket that is the name, and deletes only unlinked bracketed rows`() {
        artists()["all-h-ours"] shouldBe "All(h)ours"
        artists().containsKey("fracture-uk") shouldBe false
        artists()["plain"] shouldBe "Plain"
    }

    @Test
    fun `every new slug is the slug of its new name`() {
        val migration = File("src/main/resources/db/migration/V045__strip_annotation_brackets_from_artist_names.sql").readText()
        RENAME_ROW
            .findAll(migration)
            .map { it.destructured }
            .filter { (_, newSlug, newName) -> SlugGenerator.slugify(newName.replace("''", "'")) != newSlug }
            .map { (oldSlug, newSlug, newName) -> "$oldSlug -> '$newSlug' is named '$newName'" }
            .toList()
            .shouldBeEmpty()
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun plantArtist(
        statement: Statement,
        name: String,
        slug: String
    ) {
        statement.execute("INSERT INTO artist (name, slug) VALUES ('${name.replace("'", "''")}', '$slug')")
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', '$sourceId', DATE '2026-01-01', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        for (slug in artistSlugs) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id) " +
                    "SELECT e.id, a.id FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun artists(): Map<String, String> = query("SELECT slug, name FROM artist") { it.getString(1) to it.getString(2) }.toMap()

    private fun eventsOf(slug: String): List<String> =
        query(
            "SELECT e.source_id FROM event e JOIN event_artist ea ON ea.event_id = e.id " +
                "JOIN artist a ON a.id = ea.artist_id WHERE a.slug = '$slug'"
        ) { it.getString(1) }

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }

    private companion object {
        /** One `('old-slug', 'new-slug', 'New Name')` row of the rename list. */
        val RENAME_ROW = Regex("""\('([^']+)', '([^']+)', '((?:[^']|'')+)'\)""")
    }
}
