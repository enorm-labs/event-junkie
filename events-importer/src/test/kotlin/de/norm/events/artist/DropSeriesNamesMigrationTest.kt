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

/** Runs V070 (#2072) against planted artist rows on a database migrated to before it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropSeriesNamesMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("69").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute(
                "INSERT INTO artist (name, slug, musicbrainz_match, musicbrainz_checked_at) " +
                    "VALUES ('Reg Meuross – Sonic Morgue', 'reg-meuross-sonic-morgue', 'NONE', now())"
            )
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Half Light – Abul Mogard', 'half-light-abul-mogard')")
            // A taken slug is not evidence of the same act, so this row keeps its series.
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Delphis Orakel – Stroum', 'delphis-orakel-stroum')")
            statement.execute("INSERT INTO artist (name, slug) VALUES ('Stroum', 'stroum')")
        }
        flyway("70").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `renames a series-carrying row onto a free slug and leaves a taken one alone`() {
        names().keys shouldContainExactlyInAnyOrder listOf("reg-meuross", "abul-mogard", "delphis-orakel-stroum", "stroum")
        names()["reg-meuross"] shouldBe "Reg Meuross"
        names()["abul-mogard"] shouldBe "Abul Mogard"
        names()["delphis-orakel-stroum"] shouldBe "Delphis Orakel – Stroum"
    }

    @Test
    fun `resets the lookup verdict taken under the old name`() {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT musicbrainz_match, musicbrainz_checked_at FROM events.artist WHERE slug = 'reg-meuross'").use { rows ->
                rows.next()
                rows.getString(1) shouldBe "UNCHECKED"
                rows.getObject(2) shouldBe null
            }
        }
    }

    @Test
    fun `every new slug is the slug of its new name`() {
        names().forEach { (slug, name) ->
            if (!name.contains('–')) SlugGenerator.slugify(name) shouldBe slug
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
