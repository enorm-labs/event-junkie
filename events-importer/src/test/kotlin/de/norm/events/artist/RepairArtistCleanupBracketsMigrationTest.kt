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

/** Runs V223 against planted rows on a database migrated to before it. */
class RepairArtistCleanupBracketsMigrationTest {
    @Test
    fun `every replacement slug is the one the importer derives from its name`() {
        val rows = REPLACEMENT_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 31
        rows.forEach { (slug, name) -> artistSlugFor(name) shouldBe slug }
    }

    @Test
    fun `replaces each row with its act, reuses a stored act and drops a label`() {
        migrated { connection ->
            billing(connection, RENATE) shouldBe listOf("coco-cobra", "organza")
            billing(connection, KLUNKERKRANICH) shouldBe listOf("lmp")
            billing(connection, GOLDEN_GATE) shouldBe listOf("tindra")
            billing(connection, ELSEWHERE) shouldBe listOf("hitomi-an-toi")
            slugs(connection) shouldContainExactlyInAnyOrder listOf("coco-cobra", "organza", "lmp", "tindra", "hitomi-an-toi")
            name(connection, "organza") shouldBe "Organza"
            name(connection, "lmp") shouldBe "LMP"
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
                    plant(statement, RENATE, "coco-cobra-queerpool", "organza-elsi")
                    plant(statement, KLUNKERKRANICH, "lmp-lesmainspopulaires")
                    plant(statement, GOLDEN_GATE, "lada-recordings", "tindra")
                    // The same slug billed by another venue keeps its row and its link.
                    plant(statement, ELSEWHERE, "hitomi-an-toi")
                }
                flyway(postgres, "223").migrate()
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
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V223__repair_artist_cleanup_brackets_2026_11_03.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the migration's VALUES list. */
        val REPLACEMENT_ROW = Regex("""\('[^']+',\s*'[^']+',\s*'([^']+)',\s*'([^']+)',\s*\d\)""")

        const val RENATE = "renate:2026-10-31-halloween"
        const val KLUNKERKRANICH = "klunkerkranich:2026-10-23-babxi"
        const val GOLDEN_GATE = "golden_gate:2026-09-11-klubnacht"
        const val ELSEWHERE = "so36:1"
        val ARTISTS =
            listOf(
                "Coco Cobra [Queerpool]" to "coco-cobra-queerpool",
                "Organza [ELSI]" to "organza-elsi",
                "Organza" to "organza",
                "Lmp (LesMainsPopulaires)" to "lmp-lesmainspopulaires",
                "Lada Recordings" to "lada-recordings",
                "Tindra" to "tindra",
                "Hitomi (An Toi)" to "hitomi-an-toi"
            )
    }
}
