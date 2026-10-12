package de.norm.events.artist

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V145 (#3159) against a past Badehaus night that bills the fused `meets` row before another act. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitBadehausMeetsArtistMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("142").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            statement.execute(
                "INSERT INTO artist (name, slug) VALUES ('Roots Daughters meets La Comadre Soundsystem', '$FUSED'), " +
                    "('Roots Daughters', 'roots-daughters'), ('Gomez', 'gomez')"
            )
            plantEvent(statement, NIGHT, FUSED, "gomez")
        }
        flyway("145").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `the night bills both acts at the fused row's place`() {
        lineupOf(NIGHT) shouldContainExactly listOf("roots-daughters", "la-comadre-soundsystem", "gomez")
    }

    @Test
    fun `the fused row is gone and the existing act row is reused`() {
        query("SELECT slug FROM artist WHERE slug = '$FUSED'") { it.getString(1) }.shouldBeEmpty()
        query("SELECT count(*) FROM artist WHERE slug = 'roots-daughters'") { it.getInt(1) } shouldContainExactly listOf(1)
        query("SELECT name FROM artist WHERE slug = 'la-comadre-soundsystem'") { it.getString(1) } shouldContainExactly
            listOf("La Comadre Soundsystem")
    }

    @Test
    fun `each act's slug is the one the import derives`() {
        artistSlugFor("Roots Daughters") shouldBe "roots-daughters"
        artistSlugFor("La Comadre Soundsystem") shouldBe "la-comadre-soundsystem"
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    /** One night billing the artists of [slugs], in billing order. */
    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg slugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', replace('$sourceId', ':', '-'), DATE '2026-09-24', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        slugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order, true FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun lineupOf(sourceId: String): List<String> =
        query(
            "SELECT a.slug FROM event_artist ea JOIN artist a ON a.id = ea.artist_id JOIN event e ON e.id = ea.event_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { it.getString(1) }

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }

    private companion object {
        const val FUSED = "roots-daughters-meets-la-comadre-soundsystem"
        const val NIGHT = "badehaus:roots-daughters-meets-la-comadre-soundsystem"
    }
}
