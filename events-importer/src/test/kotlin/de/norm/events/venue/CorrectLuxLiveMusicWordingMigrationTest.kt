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

/** Runs V169 against planted venue rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrectLuxLiveMusicWordingMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "168").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO venue (name, slug, description, description_language, description_alt, " +
                    "description_alt_language) VALUES ('L.U.X', 'l-u-x', '$OLD_EN', 'en', '$OLD_DE', 'de'), " +
                    "('Other', 'other', '$OLD_EN', 'en', '$OLD_DE', 'de')"
            )
        }
        flyway(postgres, "169").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `rewrites both of L-U-X's texts`() {
        strings("SELECT description FROM venue WHERE slug = 'l-u-x'") shouldBe listOf(NEW_EN)
        strings("SELECT description_alt FROM venue WHERE slug = 'l-u-x'") shouldBe listOf(NEW_DE)
    }

    @Test
    fun `leaves another venue with the same text alone`() {
        strings("SELECT description FROM venue WHERE slug = 'other'") shouldBe listOf(OLD_EN)
        strings("SELECT description_alt FROM venue WHERE slug = 'other'") shouldBe listOf(OLD_DE)
    }

    @Test
    fun `keeps a text an operator has edited`() {
        val migration = Files.readString(Path.of(MIGRATION))
        // Rolled back, so the other tests still see the migrated row.
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO events")
                statement.execute(
                    "UPDATE venue SET description = 'Edited by hand.', description_alt = '$OLD_DE' WHERE slug = 'l-u-x'"
                )
                statement.execute(migration)
            }
            strings("SELECT description FROM venue WHERE slug = 'l-u-x'") shouldBe listOf("Edited by hand.")
            strings("SELECT description_alt FROM venue WHERE slug = 'l-u-x'") shouldBe listOf(NEW_DE)
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
        const val MIGRATION = "src/main/resources/db/migration/V169__correct_lux_live_music_wording.sql"
        const val OLD_EN =
            "A Kreuzberg bar on Schlesische Straße that has no live music by design: vinyl DJs play, with free " +
                "entry on many Thursdays."
        const val NEW_EN =
            "A Kreuzberg bar on Schlesische Straße where vinyl DJs play, with free entry on many Thursdays. " +
                "Live music is not possible in the room."
        const val OLD_DE =
            "Bar in der Schlesischen Straße, in der es bewusst keine Livemusik gibt: Hier legen Schallplatten-DJs " +
                "auf, donnerstags oft bei freiem Eintritt."
        const val NEW_DE =
            "Bar in der Schlesischen Straße, in der Schallplatten-DJs auflegen, donnerstags oft bei freiem " +
                "Eintritt. Livemusik ist in der L.U.X nicht möglich."
    }
}
