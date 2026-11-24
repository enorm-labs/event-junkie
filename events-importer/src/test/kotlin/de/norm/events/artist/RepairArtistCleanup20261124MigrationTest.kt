package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/** Runs V279 against one row of each shape, and checks every slug in the file against [artistSlugFor]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairArtistCleanup20261124MigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        flyway(postgres, "278").migrate()
        connection = connect(postgres)
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            ARTISTS.forEach { (slug, name) -> statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')") }
            LINEUPS.entries.forEachIndexed { index, (sourceId, slugs) ->
                statement.execute(
                    "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                        "SELECT id, 'Night', 'night-$index', DATE '2026-11-21', '$sourceId' FROM venue"
                )
                slugs.forEachIndexed { order, slug ->
                    statement.execute(
                        "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) SELECT e.id, a.id, 'DJ', $order, FALSE " +
                            "FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
                    )
                }
            }
        }
        flyway(postgres, "279").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `a set time leaves the act's name, onto a stored act or a new one, and a placeholder goes`() {
        lineup("kater:1961") shouldContainExactly listOf("Per Hammar|per-hammar|0", "Schmusi|schmusi|1", "Guest|guest|3")
    }

    @Test
    fun `another venue's billing of the same row stays`() {
        lineup("lux:night") shouldContainExactly listOf("00:30 Schmusi|00-30-schmusi|0")
    }

    @Test
    fun `a merge moves the loser's links and leaves its slug as an alias`() {
        lineup("kreuzwerk:night") shouldContainExactly listOf("Volvox|volvox|0")
        rows(connection, "SELECT a.slug FROM events.artist_slug_alias s JOIN events.artist a ON a.id = s.artist_id WHERE s.slug = 'dj-volvox'") {
            it.getString(1)
        } shouldContainExactly listOf("volvox")
    }

    @Test
    fun `a set note beside the billed act goes, and the act stays`() {
        lineup("tresor:night") shouldContainExactly listOf("Appleblim|appleblim|0")
        rows(connection, "SELECT slug FROM events.artist") { it.getString(1) } shouldNotContain "appleblim-grime-set"
    }

    @Test
    fun `every slug the file writes or matches is the slug of its name`() {
        val sql =
            javaClass
                .getResource("/db/migration/V279__repair_artist_cleanup_2026_11_24.sql")!!
                .readText()
                .lines()
        val renames = sql.mapNotNull { RENAME.matchEntire(it) }
        renames.size shouldBeGreaterThan 0
        assertSoftly {
            renames.forEach { withClue(it.value) { artistSlugFor(it.groupValues[2]) shouldBe it.groupValues[1] } }
        }
        val merges = sql.mapNotNull { MERGE.matchEntire(it) }
        merges.size shouldBeGreaterThan 0
        assertSoftly {
            merges.forEach { withClue(it.value) { artistSlugFor(it.groupValues[1]) shouldBe it.groupValues[2] } }
        }
    }

    private fun lineup(sourceId: String): List<String> =
        rows(
            connection,
            "SELECT concat_ws('|', a.name, a.slug, ea.billing_order) FROM events.event_artist ea JOIN events.artist a ON a.id = ea.artist_id " +
                "JOIN events.event e ON e.id = ea.event_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { it.getString(1) }

    private companion object {
        val ARTISTS =
            mapOf(
                "02-30-per-hammar" to "02:30 Per Hammar",
                "per-hammar" to "Per Hammar",
                "00-30-schmusi" to "00:30 Schmusi",
                "01-00-special-guest" to "01:00 Special Guest",
                "guest" to "Guest",
                "dj-volvox" to "Dj Volvox",
                "volvox" to "Volvox",
                "appleblim" to "Appleblim",
                "appleblim-grime-set" to "Appleblim (Grime Set)"
            )
        val LINEUPS =
            mapOf(
                "kater:1961" to listOf("02-30-per-hammar", "00-30-schmusi", "01-00-special-guest", "guest"),
                "lux:night" to listOf("00-30-schmusi"),
                "kreuzwerk:night" to listOf("dj-volvox"),
                "tresor:night" to listOf("appleblim", "appleblim-grime-set")
            )

        /** `(old_slug, prefix, slug, name)`. */
        val RENAME = Regex("""\s+\('[^']+',\s+'[a-z0-9_]+:',\s+'([^']+)',\s+'([^']+)'\)[,;]""")

        /** `(loser, loser_name, survivor)`; a drop's third value is a prefix and ends in a colon. */
        val MERGE = Regex("""\s+\('[^']+',\s+'([^']+)',\s+'([a-z0-9-]+)'\)[,;]""")
    }
}
