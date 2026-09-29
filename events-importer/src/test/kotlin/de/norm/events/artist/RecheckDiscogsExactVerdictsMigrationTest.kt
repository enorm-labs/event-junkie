package de.norm.events.artist

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V063 (#2054) against planted Discogs verdicts on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecheckDiscogsExactVerdictsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("62").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                """
                INSERT INTO artist (name, slug, musicbrainz_match, discogs_match, discogs_id, discogs_url, discogs_checked_at) VALUES
                    ('Beat It!', 'beat-it', 'NONE', 'EXACT', 6728639, 'https://www.discogs.com/artist/6728639-Beat-It!', now()),
                    ('Zoh Amba', 'zoh-amba', 'NONE', 'EXACT', 7000001, 'https://www.discogs.com/artist/1-Venue-Supplied', now()),
                    ('Nails', 'nails', 'NONE', 'AMBIGUOUS', NULL, NULL, now()),
                    ('Okkyung Lee', 'okkyung-lee', 'NONE', 'NONE', NULL, NULL, now())
                """.trimIndent()
            )
        }
        flyway("63").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `an EXACT verdict is asked again and loses the link it wrote`() {
        row("beat-it") shouldBe Row("UNCHECKED", null, null, checked = false)
    }

    @Test
    fun `an EXACT verdict keeps a link that another source gave`() {
        row("zoh-amba") shouldBe Row("UNCHECKED", null, "https://www.discogs.com/artist/1-Venue-Supplied", checked = false)
    }

    @Test
    fun `other verdicts are not touched`() {
        row("nails") shouldBe Row("AMBIGUOUS", null, null, checked = true)
        row("okkyung-lee") shouldBe Row("NONE", null, null, checked = true)
    }

    private data class Row(
        val match: String,
        val discogsId: Long?,
        val discogsUrl: String?,
        val checked: Boolean
    )

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun row(slug: String): Row =
        connection.prepareStatement("SELECT discogs_match, discogs_id, discogs_url, discogs_checked_at FROM events.artist WHERE slug = ?").use { statement ->
            statement.setString(1, slug)
            statement.executeQuery().use { rs ->
                rs.next()
                Row(rs.getString(1), rs.getObject(2) as Long?, rs.getString(3), checked = rs.getObject(4) != null)
            }
        }
}
