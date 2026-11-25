package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.scraper.stripArtistSuffix
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/** Runs V283 against a starred row with a clean twin, one without, and one corrected by hand. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CutStarredLiveMarksMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        flyway(postgres, "281").migrate()
        connection = connect(postgres)
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('Yons *Live', 'yons-live'), ('Yons', 'yons'), " +
                    "('Harald F. *Live', 'harald-f-live'), ('Tromo (Berlin)', 'tromo-live')"
            )
            statement.execute(
                "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, 'Night', 'night', DATE '2026-11-20', 'x:1' FROM venue"
            )
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) " +
                    "SELECT e.id, a.id, 'DJ', 0, FALSE FROM event e, artist a WHERE a.slug IN ('yons-live', 'harald-f-live', 'tromo-live')"
            )
        }
        flyway(postgres, "283").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `a row with a clean twin merges into it, one without is renamed, and one corrected by hand stays`() {
        query("SELECT a.name || '|' || a.slug FROM events.event_artist ea JOIN events.artist a ON a.id = ea.artist_id ORDER BY a.slug") shouldContainExactly
            listOf("Harald F.|harald-f", "Tromo (Berlin)|tromo-live", "Yons|yons")
    }

    @Test
    fun `the old slugs stay as aliases`() {
        query("SELECT s.slug || '>' || a.slug FROM events.artist_slug_alias s JOIN events.artist a ON a.id = s.artist_id ORDER BY s.slug") shouldContainExactly
            listOf("harald-f-live>harald-f", "yons-live>yons")
    }

    @Test
    fun `every row of the file is what the import makes of the old name`() {
        val rows =
            javaClass
                .getResource("/db/migration/V283__cut_starred_live_marks_from_artist_names.sql")!!
                .readText()
                .lines()
                .mapNotNull { ROW.matchEntire(it) }
        rows shouldHaveSize 8
        assertSoftly {
            rows.forEach { row ->
                val oldName = row.groupValues[2]
                val name = row.groupValues[4]
                withClue(oldName) {
                    artistSlugFor(oldName) shouldBe row.groupValues[1]
                    stripArtistSuffix(oldName) shouldBe name
                    artistSlugFor(name) shouldBe row.groupValues[3]
                }
            }
        }
    }

    private fun query(sql: String): List<String> = rows(connection, sql) { it.getString(1) }

    private companion object {
        val ROW = Regex("""\s+\('([^']+)', '([^']+)', '([^']+)', '([^']+)'\)[,;]""")
    }
}
