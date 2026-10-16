package de.norm.events.artist

import de.norm.events.flyway
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V147 (#3163) against planted Morphine Raum rows on a database migrated to before it. */
class DropMorphineActWorkArtistsMigrationTest {
    @Test
    fun `bills the act in place of the fused row and drops the work's rows`() {
        migrated(actExists = false) { connection ->
            billing(connection, ALISTER) shouldBe listOf("alister-spence" to SET_START)
            billing(connection, SIGMA).map { it.first } shouldBe listOf("antonio-raia")
            slugs(connection) shouldContainExactlyInAnyOrder listOf("alister-spence", "antonio-raia", "berlin-series-i-elsewhere")
            name(connection, "alister-spence") shouldBe "Alister Spence"
        }
    }

    @Test
    fun `reuses an act row that already exists`() {
        migrated(actExists = true) { connection ->
            billing(connection, ALISTER) shouldBe listOf("alister-spence" to SET_START)
            name(connection, "alister-spence") shouldBe "Alister SPENCE"
        }
    }

    private fun migrated(
        actExists: Boolean,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "142").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Morphine Raum', 'morphine-raum')")
                    for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    if (actExists) statement.execute("INSERT INTO artist (name, slug) VALUES ('Alister SPENCE', 'alister-spence')")
                    plant(statement, ALISTER, "alister-spence-within-without")
                    plant(statement, SIGMA, "antonio-raia", "sigma", "berlin-series-i")
                    // Another venue's row of a different slug is never touched.
                    plant(statement, "so36:1", "berlin-series-i-elsewhere")
                }
                flyway(postgres, "147").migrate()
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
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order, set_start) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order, TIMESTAMPTZ '$SET_START' FROM event e, artist a " +
                    "WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun billing(
        connection: Connection,
        sourceId: String
    ): List<Pair<String, String>> =
        rows(
            connection,
            "SELECT a.slug, to_char(ea.set_start AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM events.event_artist ea " +
                "JOIN events.event e ON e.id = ea.event_id JOIN events.artist a ON a.id = ea.artist_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { it.getString(1) to it.getString(2) }

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
        const val ALISTER = "morphine:alister-spence-within-without-live-recording"
        const val SIGMA = "morphine:sigma-berlin-series-i"
        const val SET_START = "2026-09-26T18:30:00Z"
        val ARTISTS =
            listOf(
                "Alister Spence – Within Without" to "alister-spence-within-without",
                "Antonio Raia" to "antonio-raia",
                "Σ [sigma]" to "sigma",
                "Berlin Series I" to "berlin-series-i",
                "Berlin Series I" to "berlin-series-i-elsewhere"
            )
    }
}
