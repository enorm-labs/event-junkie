package de.norm.events.artist

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V067 (#2078) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StripSquareBracketTagsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("66").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO artist (name, slug, musicbrainz_match, musicbrainz_checked_at) " +
                    "VALUES ('Endica [Esp]', 'endica-esp', 'NONE', now())"
            )
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Marí Kozlovska [Esp]', 'mari-kozlovska-esp')")
            // A taken slug is not evidence of the same act, so this row keeps its tag.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Ohnmacht [Live]', 'ohnmacht-live')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Ohnmacht', 'ohnmacht')")
            // Brackets that are the name are not in the migration.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Ø [Phase]', 'o-phase')")
        }
        flyway("67").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `renames a tagged row onto a free slug and leaves a taken one alone`() {
        names().keys shouldContainExactlyInAnyOrder listOf("endica", "mari-kozlovska", "ohnmacht-live", "ohnmacht", "o-phase")
        names()["endica"] shouldBe "Endica"
        names()["mari-kozlovska"] shouldBe "Marí Kozlovska"
        names()["ohnmacht-live"] shouldBe "Ohnmacht [Live]"
    }

    @Test
    fun `resets the lookup verdict taken under the tagged name`() {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT musicbrainz_match, musicbrainz_checked_at FROM events.artist WHERE slug = 'endica'").use { rows ->
                rows.next()
                rows.getString(1) shouldBe "UNCHECKED"
                rows.getObject(2) shouldBe null
            }
        }
    }

    @Test
    fun `every new slug is the slug of its new name`() {
        names().forEach { (slug, name) ->
            if (!name.contains('[')) SlugGenerator.slugify(name) shouldBe slug
        }
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
            statement.executeQuery("SELECT slug, name FROM events.artist").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
            }
        }
}
