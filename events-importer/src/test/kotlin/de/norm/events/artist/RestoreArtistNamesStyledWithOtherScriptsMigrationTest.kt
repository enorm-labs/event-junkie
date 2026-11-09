package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V240 against planted rows on a database migrated to before it. */
class RestoreArtistNamesStyledWithOtherScriptsMigrationTest {
    @Test
    fun `restores the two lowered names and leaves the other rows alone`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "239").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute(
                        "INSERT INTO artist (name, slug) VALUES " +
                            "('Kabλni', 'kabni'), ('Nξøn Bl△ck', 'non-blck'), ('Meduσa', 'medua'), ('Kabbel', 'kabbel')"
                    )
                }
                flyway(postgres, "240").migrate()

                rows(connection, "SELECT name FROM events.artist ORDER BY slug") { it.getString(1) } shouldContainExactly
                    listOf("Kabbel", "KABΛNI", "Meduσa", "NΞØN BL△CK")
            }
        }
    }
}
