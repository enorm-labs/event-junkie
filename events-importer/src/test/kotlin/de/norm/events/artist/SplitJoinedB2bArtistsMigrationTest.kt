package de.norm.events.artist

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V154 (#3024) against planted rows on a database migrated to before it. */
class SplitJoinedB2bArtistsMigrationTest {
    @Test
    fun `every half's slug is the one the importer derives from its name`() {
        val rows = REPLACEMENT_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 19
        rows.forEach { (slug, name) -> artistSlugFor(name) shouldBe slug }
    }

    @Test
    fun `splits two joined rows on one night into consecutive halves`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "153").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    plant(statement, ITALORAMA, "dash-b2b-lennart", "franz-scala-b2b-giulia-gutterer", "jane-fitz")
                    plant(statement, HOVR, "michael-lane-b2b")
                    // Another venue's row of the same slug keeps its link.
                    plant(statement, ELSEWHERE, "dash-b2b-lennart")
                }
                flyway(postgres, "154").migrate()

                billing(connection, ITALORAMA) shouldBe listOf("dash", "lennart", "franz-scala", "giulia-gutterer", "jane-fitz")
                billing(connection, HOVR) shouldBe listOf("michael-lane")
                billing(connection, ELSEWHERE) shouldBe listOf("dash-b2b-lennart")
                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf("dash", "lennart", "franz-scala", "giulia-gutterer", "jane-fitz", "michael-lane", "dash-b2b-lennart")
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

    private fun flyway(
        postgres: PostgreSQLContainer,
        target: String
    ): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

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

    private fun <T : Any> rows(
        connection: Connection,
        sql: String,
        read: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V154__split_joined_b2b_artists.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the migration's VALUES list. */
        val REPLACEMENT_ROW = Regex("""\('[^']+',\s*'[^']+',\s*'([^']+)',\s*'([^']+)',\s*\d\)""")

        const val ITALORAMA = "berghain:80793"
        const val HOVR = "gartn:2026-09-12-hovr-s-flowers"
        const val ELSEWHERE = "so36:1"
        val ARTISTS =
            listOf(
                "Dash B2B Lennart" to "dash-b2b-lennart",
                "Franz Scala B2B Giulia Gutterer" to "franz-scala-b2b-giulia-gutterer",
                "Jane Fitz" to "jane-fitz",
                "Michael Lane b2b" to "michael-lane-b2b"
            )
    }
}
