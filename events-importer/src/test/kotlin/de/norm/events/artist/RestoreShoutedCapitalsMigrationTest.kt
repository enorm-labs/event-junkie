package de.norm.events.artist

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Runs V055 (#1846) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreShoutedCapitalsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("53").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Ufo 361', 'ufo-361')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('\$Ono\$ Cliq', 'ono-cliq')")
            // Corrected by hand since: the migration must not overwrite it.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Lexy & K.Paul', 'lexy-k-paul')")
        }
        flyway("55").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `restores the capitals of a row that still carries the minted name`() {
        names()["ufo-361"] shouldBe "UFO 361"
        names()["ono-cliq"] shouldBe "\$ONO\$ Cliq"
    }

    @Test
    fun `leaves a row whose name changed since`() {
        names()["lexy-k-paul"] shouldBe "Lexy & K.Paul"
    }

    @Test
    fun `every slug is the slug of its new name`() {
        val migration = File("src/main/resources/db/migration/V055__restore_shouted_artist_capitals.sql").readText()
        ROW
            .findAll(migration)
            .map { it.destructured }
            .filter { (slug, _, newName) -> SlugGenerator.slugify(newName) != slug }
            .map { (slug, _, newName) -> "'$slug' is named '$newName'" }
            .toList()
            .shouldBeEmpty()
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun names(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery("SELECT slug, name FROM artist").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap()
            }
        }

    private companion object {
        /** One `('slug', 'Old Name', 'New Name')` row of the rename list. */
        val ROW = Regex("""\('([^']+)', '([^']+)', '([^']+)'\)""")
    }
}
