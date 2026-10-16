package de.norm.events.venue

import de.norm.events.flyway
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

/** Runs V162 (#3362) against planted venue rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NameYaamFoundingYearMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "161").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO venue (name, slug, description, description_language, description_alt, " +
                    "description_alt_language) VALUES ('YAAM', 'yaam', '$OLD', 'en', '$GERMAN', 'de'), " +
                    "('Other', 'other', '$OLD', 'en', NULL, NULL)"
            )
        }
        flyway(postgres, "162").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `rewrites YAAM's English text and keeps its German one`() {
        strings("SELECT description FROM venue WHERE slug = 'yaam'") shouldBe listOf(NEW)
        strings("SELECT description_alt FROM venue WHERE slug = 'yaam'") shouldBe listOf(GERMAN)
    }

    @Test
    fun `leaves another venue with the same text alone`() {
        strings("SELECT description FROM venue WHERE slug = 'other'") shouldBe listOf(OLD)
    }

    @Test
    fun `keeps a text an operator has edited`() {
        val migration = Files.readString(Path.of(MIGRATION))
        // Rolled back, so the other tests still see the migrated row.
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO events")
                statement.execute("UPDATE venue SET description = 'Edited by hand.' WHERE slug = 'yaam'")
                statement.execute(migration)
            }
            strings("SELECT description FROM venue WHERE slug = 'yaam'") shouldBe listOf("Edited by hand.")
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    private fun strings(query: String): List<String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(query).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private companion object {
        const val MIGRATION = "src/main/resources/db/migration/V162__name_yaam_founding_year_in_english.sql"
        const val OLD =
            "A non-profit socio-cultural project on an 8,000 m² riverside site at Schillingbrücke since 2014, " +
                "run by Kult e.V., with open-air reggae and hip-hop DJ days, festivals and evening events in its " +
                "Cool Runnings clubhouse."
        const val NEW =
            "A non-profit socio-cultural project run by Kult e.V. since 1994, on an 8,000 m² riverside site at " +
                "Schillingbrücke since 2014, with open-air reggae and hip-hop DJ days, festivals and evening events " +
                "in its Cool Runnings clubhouse."
        const val GERMAN = "Seit 1994 gibt es das vom gemeinnützigen KULT e.V. getragene YAAM."
    }
}
