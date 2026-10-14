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

/** Runs V157 against planted rows on a database migrated to before it. */
class RepairArtistCleanupPastRowsMigrationTest {
    @Test
    fun `every replacement slug is the one the importer derives from its name`() {
        val rows = REPLACEMENT_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 35
        rows.forEach { (slug, name) -> artistSlugFor(name) shouldBe slug }
    }

    @Test
    fun `splits a twelve-name row in place, drops a placeholder and tidies two rows`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "155").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    statement.execute("UPDATE artist SET musicbrainz_match = 'EXACT' WHERE slug = 'saturday-night'")
                    plant(statement, HASE, "kopf-und-horer", TRAGIK, "evox")
                    plant(statement, KLUNKER, "line-up-tba", "pornbugs-host")
                    plant(statement, RENATE, "lux")
                }
                flyway(postgres, "157").migrate()

                billing(connection, HASE) shouldBe listOf("kopf-und-horer") + TRAGIK_HALVES + "evox"
                billing(connection, KLUNKER) shouldBe listOf("pornbugs-host")
                billing(connection, RENATE) shouldBe listOf("lux")
                rows(connection, "SELECT name FROM events.artist WHERE slug = 'lux'") { it.getString(1) } shouldBe listOf("Lux")
                slugs(connection) shouldContainExactlyInAnyOrder listOf("kopf-und-horer", "evox", "pornbugs-host", "lux") + TRAGIK_HALVES
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
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V157__repair_artist_cleanup_past_rows.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the migration's VALUES list. */
        val REPLACEMENT_ROW = Regex("""\('[^']+',\s*'[^']+',\s*'([^']+)',\s*'([^']+)',\s*\d+\)""")

        const val HASE = "der_weisse_hase:2026-09-20-die-lust-am-feiern"
        const val KLUNKER = "klunkerkranich:disco-royale"
        const val RENATE = "renate:klubnacht"
        const val TRAGIK = "tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese"
        val TRAGIK_HALVES =
            listOf(
                "tragik",
                "stempelmann",
                "coon",
                "jordan",
                "niko-incravalle",
                "onkel-tom",
                "bad-boy",
                "das-amt",
                "shorty",
                "andy-m",
                "nico-brix",
                "andre-wiese"
            )
        val ARTISTS =
            listOf(
                "Kopf & Hörer" to "kopf-und-horer",
                "Tragik * Stempelmann * Coon * Jordan * Niko Incravalle * Onkel Tom * Bad Boy * Das Amt * Shorty * Andy M * Nico Brix" to TRAGIK,
                "Evox" to "evox",
                "Line Up tba." to "line-up-tba",
                "Host" to "pornbugs-host",
                "Lux." to "lux",
                "Saturday Night" to "saturday-night"
            )
    }
}
