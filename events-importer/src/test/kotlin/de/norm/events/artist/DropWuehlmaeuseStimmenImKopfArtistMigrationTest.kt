package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.scraper.EventSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs V277 (#5452) against the row as production holds it: one past Wühlmäuse show bills it alone. A second database
 * plants the same name on another venue's night too, which the drop leaves.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropWuehlmaeuseStimmenImKopfArtistMigrationTest {
    private val alone = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private val shared = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var aloneConnection: Connection
    private lateinit var sharedConnection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        aloneConnection = planted(alone, mapOf(WUEHLMAEUSE_NIGHT to listOf("stimmen-im-kopf", "die-hosts")))
        sharedConnection = planted(shared, mapOf(WUEHLMAEUSE_NIGHT to listOf("stimmen-im-kopf"), "lux:podcast-night" to listOf("stimmen-im-kopf")))
    }

    @AfterAll
    fun stop() {
        aloneConnection.close()
        sharedConnection.close()
        alone.stop()
        shared.stop()
    }

    @Test
    fun `the only statement drops the slug the importer derives, for the source's own prefix`() {
        val statements = Files.readAllLines(MIGRATION).filterNot { it.isBlank() || it.startsWith("--") }
        statements shouldContainExactly
            listOf("SELECT drop_artist_billed_only_by('${artistSlugFor("Stimmen im Kopf")}', '${EventSource.WUEHLMAEUSE.sourceIdPrefix}');")
    }

    @Test
    fun `drops the row Die Wuehlmaeuse alone bills, and keeps the event and another act`() {
        strings(aloneConnection, "SELECT slug FROM events.artist") shouldContainExactly listOf("die-hosts")
        strings(aloneConnection, "SELECT source_id FROM events.event") shouldContainExactly listOf(WUEHLMAEUSE_NIGHT)
        strings(aloneConnection, "SELECT a.slug FROM events.event_artist ea JOIN events.artist a ON a.id = ea.artist_id") shouldContainExactly
            listOf("die-hosts")
    }

    @Test
    fun `keeps the row and both billings when another venue bills it too`() {
        strings(sharedConnection, "SELECT slug FROM events.artist") shouldContainExactly listOf("stimmen-im-kopf")
        strings(sharedConnection, "SELECT e.source_id FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id") shouldContainExactlyInAnyOrder
            listOf(WUEHLMAEUSE_NIGHT, "lux:podcast-night")
    }

    private fun planted(
        postgres: PostgreSQLContainer,
        billings: Map<String, List<String>>
    ): Connection {
        postgres.start()
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "276").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            billings.values.flatten().distinct().forEach { slug ->
                statement.execute("INSERT INTO artist (name, slug) VALUES ('$slug', '$slug')")
            }
            billings.keys.forEachIndexed { index, sourceId ->
                statement.execute(
                    "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                        "SELECT id, 'Stimmen im Kopf', 'night-$index', DATE '2026-11-23', '$sourceId' FROM venue"
                )
            }
            for ((sourceId, slugs) in billings) {
                slugs.forEachIndexed { order, slug ->
                    statement.execute(
                        "INSERT INTO event_artist (event_id, artist_id, billing_order) " +
                            "SELECT e.id, a.id, $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
                    )
                }
            }
        }
        flyway(postgres, "277").migrate()
        return connection
    }

    private fun strings(
        connection: Connection,
        sql: String
    ): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private companion object {
        const val WUEHLMAEUSE_NIGHT = "wuehlmaeuse:stimmen-kopf-abgruende-psyche-2026-11-23-2000"
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V277__drop_wuehlmaeuse_stimmen_im_kopf_artist.sql")
    }
}
