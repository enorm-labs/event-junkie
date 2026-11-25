package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/** Runs V280 against the band's concert and the DJ's night, both stored on the DJ's row. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GiveHuxleysWilcoItsOwnRowMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        flyway(postgres, "279").migrate()
        connection = connect(postgres)
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Wilco', 'wilco')")
            listOf("huxleys:2027-06-26-wilco" to "HEADLINER", "frannz:1" to "DJ").forEachIndexed { index, (sourceId, role) ->
                statement.execute(
                    "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                        "SELECT id, 'Night', 'night-$index', DATE '2027-06-26', '$sourceId' FROM venue"
                )
                statement.execute(
                    "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) SELECT e.id, a.id, '$role', 0, FALSE " +
                        "FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = 'wilco'"
                )
            }
        }
        flyway(postgres, "280").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `the concert moves to the band's row and keeps its role`() {
        lineup("huxleys:2027-06-26-wilco") shouldContainExactly listOf("Wilco|wilco-2|HEADLINER")
    }

    @Test
    fun `the DJ's night stays on the DJ's row`() {
        lineup("frannz:1") shouldContainExactly listOf("Wilco|wilco|DJ")
    }

    @Test
    fun `the new row has the slug the importer resolves`() {
        artistSlugFor("Wilco", "huxleys:2027-06-26-wilco") shouldBe "wilco-2"
    }

    private fun lineup(sourceId: String): List<String> =
        rows(
            connection,
            "SELECT concat_ws('|', a.name, a.slug, ea.role) FROM events.event_artist ea JOIN events.artist a ON a.id = ea.artist_id " +
                "JOIN events.event e ON e.id = ea.event_id WHERE e.source_id = '$sourceId'"
        ) { it.getString(1) }
}
