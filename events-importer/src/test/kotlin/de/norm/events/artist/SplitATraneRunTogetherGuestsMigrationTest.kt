package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Plants event 5171's line-up (#4374), runs V216, and runs its SQL a second time. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitATraneRunTogetherGuestsMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection
    private lateinit var afterFirstRun: List<String>

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "214").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('Sebastian Merk-John Schröder', '$FUSED'), " +
                    "('Andreas Schmidt', 'andreas-schmidt'), ('Sebastian Merk', 'sebastian-merk'), ('Late Guest', 'late-guest')"
            )
            plant(statement, NIGHT, "andreas-schmidt" to "HEADLINER", FUSED to "SUPPORT", "late-guest" to "SUPPORT")
        }
        flyway(postgres, "216").migrate()
        afterFirstRun = billing()
        rerun()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `the two guests take the fused row's place, role and order`() {
        billing() shouldContainExactly
            listOf("andreas-schmidt 0 HEADLINER", "sebastian-merk 1 SUPPORT", "john-schroder 2 SUPPORT", "late-guest 3 SUPPORT")
    }

    @Test
    fun `the fused row is gone, an existing guest row is reused and a missing one is created`() {
        names(FUSED).shouldBeEmpty()
        names("sebastian-merk") shouldContainExactly listOf("Sebastian Merk")
        names("john-schroder") shouldContainExactly listOf("John Schröder")
    }

    @Test
    fun `each slug is the one the importer derives`() {
        artistSlugFor("Sebastian Merk-John Schröder") shouldBe FUSED
        artistSlugFor("Sebastian Merk") shouldBe "sebastian-merk"
        artistSlugFor("John Schröder") shouldBe "john-schroder"
    }

    @Test
    fun `a second run changes nothing`() {
        billing() shouldBe afterFirstRun
    }

    /** V216's SQL once more, in one transaction: its temporary tables drop on commit. */
    private fun rerun() {
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL search_path TO events")
            statement.execute(Files.readString(MIGRATION))
        }
        connection.commit()
        connection.autoCommit = true
    }

    /** One night billing [links], each a slug and its role, in billing order. */
    private fun plant(
        statement: Statement,
        sourceId: String,
        vararg links: Pair<String, String>
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'ANDREAS SCHMIDT & FRIENDS', 'fixture-night', DATE '2026-11-02', '$sourceId' FROM venue"
        )
        links.forEachIndexed { order, (slug, role) ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, '$role', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun billing(): List<String> =
        rows(
            connection,
            "SELECT a.slug, ea.billing_order, ea.role FROM events.event_artist ea " +
                "JOIN events.event e ON e.id = ea.event_id JOIN events.artist a ON a.id = ea.artist_id " +
                "WHERE e.source_id = '$NIGHT' ORDER BY ea.billing_order"
        ) { "${it.getString(1)} ${it.getInt(2)} ${it.getString(3)}" }

    private fun names(slug: String): List<String> = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V216__split_atrane_run_together_guests.sql")

        const val FUSED = "sebastian-merk-john-schroder"
        const val NIGHT = "a_trane:a-trane-praesentiertandreas-schmidt-friendsheute-mit-170"
    }
}
