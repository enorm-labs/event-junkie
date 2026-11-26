package de.norm.events.event

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/** Runs V285 on a database that holds the retitled Uber Arena event, and on one that does not (#5560). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreateEventSlugAliasMigrationTest {
    private val withEvent = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private val withoutEvent = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var withEventConnection: Connection
    private lateinit var withoutEventConnection: Connection

    @BeforeAll
    fun migrate() {
        withEvent.start()
        withoutEvent.start()
        flyway(withEvent, "283").migrate()
        withEventConnection = connect(withEvent)
        withEventConnection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Uber Arena', 'uber-arena')")
            statement.execute(
                "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                    "SELECT id, 'Konstantinos Argiros', '2026-11-25-uber-arena-konstantinos-argiros', DATE '2026-11-25', 'uber:2962' FROM venue"
            )
        }
        flyway(withEvent, "285").migrate()
        flyway(withoutEvent, "285").migrate()
        withoutEventConnection = connect(withoutEvent)
    }

    @AfterAll
    fun stop() {
        withEventConnection.close()
        withoutEventConnection.close()
        withEvent.stop()
        withoutEvent.stop()
    }

    @Test
    fun `the old slug of the retitled event resolves to it`() {
        rows(
            withEventConnection,
            "SELECT a.slug || '>' || e.slug FROM events.event_slug_alias a JOIN events.event e ON e.id = a.event_id"
        ) { it.getString(1) } shouldContainExactly listOf("2026-11-25-uber-arena-wird>2026-11-25-uber-arena-konstantinos-argiros")
    }

    @Test
    fun `without the event the migration seeds nothing`() {
        rows(withoutEventConnection, "SELECT slug FROM events.event_slug_alias") { it.getString(1) }.shouldBeEmpty()
    }
}
