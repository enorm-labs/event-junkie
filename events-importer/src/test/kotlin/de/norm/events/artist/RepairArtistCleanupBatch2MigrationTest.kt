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

/** Runs V150 against planted rows on a database migrated to before it. */
class RepairArtistCleanupBatch2MigrationTest {
    @Test
    fun `every replacement slug is the one the importer derives from its name`() {
        val rows = REPLACEMENT_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 33
        rows.forEach { (slug, name) -> artistSlugFor(name) shouldBe slug }
    }

    @Test
    fun `replaces each row with its acts in its place and drops it`() {
        migrated { connection ->
            billing(connection, HOPPETOSSE) shouldBe listOf("ron-obvious", "sisi", "dj-pipe")
            billing(connection, TRANE) shouldBe listOf("tony-hurdle", "tc-the-3rd", "andrea-marcelli")
            billing(connection, HASE_ONE) shouldBe listOf("emma")
            billing(connection, HASE_EIGHT) shouldBe listOf("emma", "youston")
            billing(connection, TEMPODROM).shouldBe(emptyList())
            billing(connection, ELSEWHERE) shouldBe listOf("sisi-2")
            slugs(connection) shouldContainExactlyInAnyOrder
                listOf("ron-obvious", "sisi", "dj-pipe", "tony-hurdle", "tc-the-3rd", "andrea-marcelli", "emma", "youston", "sisi-2")
            name(connection, "tony-hurdle") shouldBe "Tony Hurdle"
        }
    }

    @Test
    fun `reuses an act row that already exists`() {
        migrated(existing = listOf("SISI" to "sisi")) { connection ->
            billing(connection, HOPPETOSSE) shouldBe listOf("ron-obvious", "sisi", "dj-pipe")
            name(connection, "sisi") shouldBe "SISI"
        }
    }

    private fun migrated(
        existing: List<Pair<String, String>> = emptyList(),
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "149").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in ARTISTS + existing) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    plant(statement, HOPPETOSSE, "ron-obvious", "sisi-2", "dj-pipe")
                    plant(statement, TRANE, "tony-hurdle-invites-tc-the-3rd", "andrea-marcelli")
                    plant(statement, HASE_ONE, "emma-1")
                    plant(statement, HASE_EIGHT, "emma-8-youston")
                    plant(statement, TEMPODROM, "missverstehen-sie-mich-richtig-robert-habeck-zu-gast-bei-maja-gopel")
                    // The same slug billed by another venue keeps its row and its link.
                    plant(statement, ELSEWHERE, "sisi-2")
                }
                flyway(postgres, "150").migrate()
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
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-09-26', '$sourceId' FROM venue"
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

    private fun <T : Any> rows(
        connection: Connection,
        sql: String,
        read: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V150__repair_artist_cleanup_batch_2.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the migration's VALUES list. */
        val REPLACEMENT_ROW = Regex("""\('[^']+',\s*'[^']+',\s*'([^']+)',\s*'([^']+)',\s*\d\)""")

        const val HOPPETOSSE = "ms_hoppetosse:7236"
        const val TRANE = "a_trane:day1"
        const val HASE_ONE = "der_weisse_hase:tanzen-hilft"
        const val HASE_EIGHT = "der_weisse_hase:trnc"
        const val TEMPODROM = "tempodrom:missverstehen"
        const val ELSEWHERE = "so36:1"
        val ARTISTS =
            listOf(
                "Ron Obvious" to "ron-obvious",
                "SiSi (2)" to "sisi-2",
                "DJ Pipe" to "dj-pipe",
                "Tony Hurdle Invites TC The 3RD" to "tony-hurdle-invites-tc-the-3rd",
                "Andrea Marcelli" to "andrea-marcelli",
                "Emma (1)" to "emma-1",
                "Emma (8)Youston" to "emma-8-youston",
                "Missverstehen Sie Mich Richtig" to "missverstehen-sie-mich-richtig-robert-habeck-zu-gast-bei-maja-gopel"
            )
    }
}
