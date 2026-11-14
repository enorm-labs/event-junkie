package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V248 against planted rows on a database migrated to before it. */
class RenameKeinGewohnlicherSturmMigrationTest {
    private fun namesAfterV248(plantedNames: String): List<String> =
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "247").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO artist (name, slug) VALUES $plantedNames")
                }
                flyway(postgres, "248").migrate()
                rows(connection, "SELECT name FROM events.artist ORDER BY slug") { it.getString(1) }
            }
        }

    @Test
    fun `renames the shouted row and leaves another row alone`() {
        namesAfterV248("('KEIN GEWöHNLICHER STURM', 'kein-gewohnlicher-sturm'), ('GEWöHNLICHER STURM', 'gewohnlicher-sturm')") shouldContainExactly
            listOf("GEWöHNLICHER STURM", "Kein Gewöhnlicher Sturm")
    }

    @Test
    fun `leaves a row renamed by hand alone`() {
        namesAfterV248("('Kein gewöhnlicher Sturm', 'kein-gewohnlicher-sturm')") shouldContainExactly listOf("Kein gewöhnlicher Sturm")
    }

    @Test
    fun `the new name keeps the slug of the row`() {
        artistSlugFor("Kein Gewöhnlicher Sturm") shouldBe "kein-gewohnlicher-sturm"
    }
}
