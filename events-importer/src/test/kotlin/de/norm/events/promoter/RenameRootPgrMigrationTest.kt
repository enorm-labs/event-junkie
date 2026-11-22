package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/** Runs V274 against planted rows on a database migrated to before it. */
class RenameRootPgrMigrationTest {
    @Test
    fun `the slug in the file is the one the importer derives`() {
        assertSoftly {
            canonicalPromoterName("Root PGR GmbH") shouldBe "Root PGR"
            SlugGenerator.slugify("Root PGR") shouldBe "root-pgr"
        }
    }

    @Test
    fun `renames the stored row in place and keeps its link`() {
        migrated("Root Pgr") { connection ->
            assertSoftly {
                promoters(connection) shouldContainExactly listOf("root-pgr Root PGR")
                linked(connection) shouldContainExactly listOf("root-pgr")
            }
        }
    }

    @Test
    fun `leaves a row a person has renamed since alone`() {
        migrated("Root P.G.R.") { connection ->
            promoters(connection) shouldContainExactly listOf("root-pgr Root P.G.R.")
        }
    }

    /** Plants `root-pgr` under [name] and links it to one night. */
    private fun migrated(
        name: String,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            connect(postgres).use { connection ->
                flyway(postgres, "273").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT id, 'Fixture night', 'fixture-night', DATE '2027-11-23', 'hole44:fixture' FROM venue"
                    )
                    statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', 'root-pgr')")
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p WHERE p.slug = 'root-pgr'"
                    )
                }
                flyway(postgres, "274").migrate()
                check(connection)
            }
        }
    }

    private fun promoters(connection: Connection): List<String> =
        rows(connection, "SELECT slug || ' ' || name FROM events.promoter ORDER BY slug") { it.getString(1) }

    private fun linked(connection: Connection): List<String> =
        rows(connection, "SELECT p.slug FROM events.event_promoter ep JOIN events.promoter p ON p.id = ep.promoter_id") { it.getString(1) }
}
