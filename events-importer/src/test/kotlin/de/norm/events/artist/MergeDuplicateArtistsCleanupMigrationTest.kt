package de.norm.events.artist

import de.norm.events.flyway
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V152 against planted rows on a database migrated to before it. */
class MergeDuplicateArtistsCleanupMigrationTest {
    @Test
    fun `every pair folds onto its survivor in the normalizer too`() {
        val rows = MERGE_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues.drop(1) }.toList()
        rows.size shouldBe 13
        rows.forEach { (_, survivor, name) ->
            artistSlugFor(name) shouldBe survivor
            canonicalArtistName(name) shouldBe name
        }
    }

    @Test
    fun `moves the loser's nights onto the survivor and deletes the loser`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "151").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Alte Kantine', 'alte-kantine')")
                    for ((name, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
                    plant(statement, "k1", "dj-buttchin", "dj-sterni")
                    // A night that bills both spellings keeps one link.
                    plant(statement, "k2", "buttchin", "dj-buttchin")
                    // A survivor that does not exist yet is created.
                    plant(statement, "k3", "traumamia")
                }
                flyway(postgres, "152").migrate()

                billing(connection, "k1") shouldBe listOf("buttchin", "sterni")
                billing(connection, "k2") shouldBe listOf("buttchin")
                billing(connection, "k3") shouldBe listOf("trauma-mia")
                slugs(connection) shouldContainExactlyInAnyOrder listOf("buttchin", "sterni", "trauma-mia")
                name(connection, "sterni") shouldBe "Sterni"
            }
        }
    }

    private fun plant(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '$sourceId', DATE '2026-09-26', 'alte_kantine:$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'DJ', $order FROM event e, artist a WHERE e.slug = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun billing(
        connection: Connection,
        eventSlug: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.slug = '$eventSlug' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun name(
        connection: Connection,
        slug: String
    ): String = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private fun <T : Any> rows(
        connection: Connection,
        sql: String,
        read: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V152__merge_duplicate_artists_cleanup.sql")

        /** One `(loser, survivor, survivor_name)` row of the merge list. */
        val MERGE_ROW = Regex("""\('([^']+)',\s*'([^']+)',\s*'([^']+)'\)""")

        val ARTISTS =
            listOf(
                "DJ Buttchin" to "dj-buttchin",
                "Buttchin" to "buttchin",
                "DJ Sterni" to "dj-sterni",
                "sterni" to "sterni",
                "TraumaMia" to "traumamia"
            )
    }
}
