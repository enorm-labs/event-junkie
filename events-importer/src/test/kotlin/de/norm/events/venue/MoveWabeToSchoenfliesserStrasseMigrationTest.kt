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

/** Runs V174 against planted venue rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoveWabeToSchoenfliesserStrasseMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "170").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO venue (name, slug, address, postal_code, latitude, longitude) VALUES " +
                    "('WABE', 'wabe', '$OLD_ADDRESS', '10405', 52.537507, 13.430951), " +
                    "('Other', 'other', '$OLD_ADDRESS', '10405', 52.537507, 13.430951)"
            )
        }
        flyway(postgres, "174").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `moves WABE to its interim hall`() {
        strings(
            "SELECT address || ' ' || postal_code || ' ' || latitude || ' ' || longitude FROM venue WHERE slug = 'wabe'"
        ) shouldBe listOf("Schönfließer Straße 7 10439 52.553381 13.408873")
    }

    @Test
    fun `leaves another venue at the same address alone`() {
        strings("SELECT address FROM venue WHERE slug = 'other'") shouldBe listOf(OLD_ADDRESS)
    }

    @Test
    fun `keeps an address an operator has edited`() {
        val migration = Files.readString(Path.of(MIGRATION))
        // Rolled back, so the other tests still see the migrated row.
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO events")
                statement.execute(
                    "UPDATE venue SET address = 'Edited by hand', postal_code = '10405' WHERE slug = 'wabe'"
                )
                statement.execute(migration)
            }
            strings("SELECT address FROM venue WHERE slug = 'wabe'") shouldBe listOf("Edited by hand")
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
        const val MIGRATION = "src/main/resources/db/migration/V174__move_wabe_to_schoenfliesser_strasse.sql"
        const val OLD_ADDRESS = "Danziger Straße 101"
    }
}
