package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V224 against planted rows on a database migrated to before it. */
class RepairArtistCleanupNotesMigrationTest {
    @Test
    fun `every replacement slug is the one the importer derives from its name`() {
        val rows = REPLACEMENT_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 16
        rows.forEach { (slug, name) -> artistSlugFor(name) shouldBe slug }
    }

    @Test
    fun `splits a row into its acts in their places, reuses a stored act and drops a placeholder`() {
        migrated { connection ->
            billing(connection, WILD_AT_HEART) shouldBe listOf("the-devil-n-us", "dj-peter", "louie-wray")
            billing(connection, RICHTEN25) shouldBe listOf("ackern-76", "chris-pitsiokos")
            billing(connection, HASE) shouldBe listOf("sudo")
            billing(connection, ELSEWHERE) shouldBe listOf("ackern-76-chris-pitsiokos")
            slugs(connection) shouldContainExactlyInAnyOrder
                listOf("the-devil-n-us", "dj-peter", "louie-wray", "ackern-76", "chris-pitsiokos", "ackern-76-chris-pitsiokos", "sudo", "m-o-e")
            name(connection, "dj-peter") shouldBe "DJ Peter"
        }
    }

    @Test
    fun `clears M_O_E_'s wrong MusicBrainz match and the facts it copied`() {
        migrated { connection ->
            rows(connection, "SELECT musicbrainz_id, musicbrainz_match, description FROM events.artist WHERE slug = 'm-o-e'") {
                listOf(it.getString(1), it.getString(2), it.getString(3))
            }.single() shouldBe listOf(null, "AMBIGUOUS", null)
        }
    }

    private fun migrated(check: (Connection) -> Unit) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "222").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    statement.execute(
                        "INSERT INTO artist (name, slug, musicbrainz_id, musicbrainz_match, description) " +
                            "VALUES ('M.O.E.', 'm-o-e', '$WRONG_MBID', 'EXACT', 'A Japanese group.')"
                    )
                    plant(statement, WILD_AT_HEART, "the-devil-n-us", "djs-peter-louie-wray")
                    plant(statement, RICHTEN25, "ackern-76-chris-pitsiokos")
                    plant(statement, HASE, "sudo", "surprise")
                    // The same slug billed by another venue keeps its row and its link.
                    plant(statement, ELSEWHERE, "ackern-76-chris-pitsiokos")
                }
                flyway(postgres, "224").migrate()
                check(connection)
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
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-31', '$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'DJ', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun billing(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun name(
        connection: Connection,
        slug: String
    ): String = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V224__repair_artist_cleanup_notes_and_splits_2026_11_03.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the migration's VALUES list. */
        val REPLACEMENT_ROW = Regex("""\('[^']+',\s*'[^']+',\s*'([^']+)',\s*'([^']+)',\s*\d\)""")

        const val WRONG_MBID = "903a3bd5-4c34-43da-9217-acf8b72a1f04"
        const val WILD_AT_HEART = "wild_at_heart:2027-02-06-the-devil-n-us"
        const val RICHTEN25 = "richten25:2026-11-05"
        const val HASE = "der_weisse_hase:2026-11-27-t-i-h"
        const val ELSEWHERE = "so36:1"
        val ARTISTS =
            listOf(
                "The Devil N Us" to "the-devil-n-us",
                "DJs Peter + Louie Wray" to "djs-peter-louie-wray",
                "Ackern 76 + Chris Pitsiokos" to "ackern-76-chris-pitsiokos",
                "Chris Pitsiokos" to "chris-pitsiokos",
                "SUDO" to "sudo",
                "Surprise" to "surprise"
            )
    }
}
