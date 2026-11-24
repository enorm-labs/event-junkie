package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/** Runs V278 (#5468) against the two fused rows, one billed with a later act and one billed by another venue too. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitBarJederVernunftDuosMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        flyway(postgres, "277").migrate()
        connection = connect(postgres)
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            BILLINGS.values.flatten().distinct().forEach { name ->
                statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '${artistSlugFor(name)}')")
            }
            BILLINGS.entries.forEachIndexed { index, (sourceId, names) ->
                statement.execute(
                    "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                        "SELECT id, 'Night', 'night-$index', DATE '2026-11-24', '$sourceId' FROM venue"
                )
                names.forEachIndexed { order, name ->
                    statement.execute(
                        "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) SELECT e.id, a.id, 'HEADLINER', $order, TRUE " +
                            "FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '${artistSlugFor(name)}'"
                    )
                }
            }
        }
        flyway(postgres, "278").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `each name takes the fused act's place and a later act moves down`() {
        lineup(CARRINGTON_NIGHT) shouldContainExactly
            listOf("Rebecca Carrington|rebecca-carrington|0|HEADLINER|t", "Martina Colli|martina-colli|1|HEADLINER|t", "Guest|guest|2|HEADLINER|t")
        lineup(ALEARDI_NIGHT) shouldContainExactly
            listOf("Pasquale Aleardi|pasquale-aleardi|0|HEADLINER|t", "Die Phonauten|die-phonauten|1|HEADLINER|t")
    }

    @Test
    fun `a fused row another venue bills keeps that billing, and a one-word duo stays`() {
        lineup(OTHER_VENUE_NIGHT) shouldContainExactly listOf("$ALEARDI|pasquale-aleardi-die-phonauten|0|HEADLINER|t")
        lineup(DUO_NIGHT) shouldContainExactly listOf("Heller & Bolam|heller-bolam|0|HEADLINER|t")
        rows(connection, "SELECT slug FROM events.artist") { it.getString(1) } shouldContainExactlyInAnyOrder
            listOf(
                "rebecca-carrington",
                "martina-colli",
                "pasquale-aleardi",
                "die-phonauten",
                "pasquale-aleardi-die-phonauten",
                "heller-bolam",
                "guest"
            )
    }

    private fun lineup(sourceId: String): List<String> =
        rows(
            connection,
            "SELECT concat_ws('|', a.name, a.slug, ea.billing_order, ea.role, CASE WHEN ea.title_derived THEN 't' ELSE 'f' END) " +
                "FROM events.event_artist ea JOIN events.artist a ON a.id = ea.artist_id JOIN events.event e ON e.id = ea.event_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { it.getString(1) }

    private companion object {
        const val CARRINGTON_NIGHT = "bar_jeder_vernunft:2026-11-24-music-the-movies"
        const val ALEARDI_NIGHT = "bar_jeder_vernunft:2026-11-25-liebe-oder-show"
        const val OTHER_VENUE_NIGHT = "lux:aleardi-night"
        const val DUO_NIGHT = "bar_jeder_vernunft:2027-02-13-l-is-for"
        const val ALEARDI = "Pasquale Aleardi & Die Phonauten"
        val BILLINGS =
            mapOf(
                CARRINGTON_NIGHT to listOf("Rebecca Carrington & Martina Colli", "Guest"),
                ALEARDI_NIGHT to listOf(ALEARDI),
                OTHER_VENUE_NIGHT to listOf(ALEARDI),
                DUO_NIGHT to listOf("Heller & Bolam")
            )
    }
}
