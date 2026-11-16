package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V256 against planted rows on a database migrated to before it. */
class RenameLccProductionsMigrationTest {
    @Test
    fun `every slug in the file is the one the importer derives`() {
        assertSoftly {
            SlugGenerator.slugify(canonicalPromoterName("www.Lcc-productions.com")) shouldBe "lcc-productions"
            SlugGenerator.slugify("www.Lcc-productions.com") shouldBe "www-lcc-productions-com"
        }
    }

    @Test
    fun `renames the row in place and keeps its link and follow`() {
        migrated(listOf("www.Lcc-productions.com" to "www-lcc-productions-com")) { connection ->
            assertSoftly {
                promoters(connection) shouldContainExactly listOf("lcc-productions LCC Productions")
                linked(connection) shouldContainExactly listOf("lcc-productions")
                follows(connection) shouldContainExactly listOf("lcc-productions")
            }
        }
    }

    @Test
    fun `moves the link onto a clean row that exists and drops the old one`() {
        migrated(listOf("www.Lcc-productions.com" to "www-lcc-productions-com", "LCC Productions" to "lcc-productions")) { connection ->
            assertSoftly {
                promoters(connection) shouldContainExactly listOf("lcc-productions LCC Productions")
                linked(connection) shouldContainExactly listOf("lcc-productions")
                follows(connection) shouldContainExactly listOf("lcc-productions")
            }
        }
    }

    @Test
    fun `changes nothing on a database without the row`() {
        migrated(listOf("LCC" to "lcc")) { connection ->
            promoters(connection) shouldContainExactly listOf("lcc LCC")
        }
    }

    /** Plants each row, links the first to one night, and lets one account follow it. */
    private fun migrated(
        promoters: List<Pair<String, String>>,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "255").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT id, 'Fixture night', 'fixture-night', DATE '2027-10-22', 'so36:103170' FROM venue"
                    )
                    statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('fan@example.de', 'de')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    val promoter = promoters.first().second
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p WHERE p.slug = '$promoter'"
                    )
                    statement.execute(
                        "INSERT INTO accounts.follow (account_id, kind, entity_id) SELECT ac.id, 'promoter', p.id " +
                            "FROM accounts.account ac, promoter p WHERE p.slug = '$promoter'"
                    )
                }
                flyway(postgres, "256").migrate()
                check(connection)
            }
        }
    }

    private fun promoters(connection: Connection): List<String> =
        rows(connection, "SELECT slug || ' ' || name FROM events.promoter ORDER BY slug") { it.getString(1) }

    private fun linked(connection: Connection): List<String> =
        rows(connection, "SELECT p.slug FROM events.event_promoter ep JOIN events.promoter p ON p.id = ep.promoter_id") { it.getString(1) }

    /** Every promoter follow by slug; a follow whose id names no row shows its id instead. */
    private fun follows(connection: Connection): List<String> =
        rows(
            connection,
            "SELECT COALESCE(p.slug, f.entity_id::text) FROM accounts.follow f " +
                "LEFT JOIN events.promoter p ON p.id = f.entity_id WHERE f.kind = 'promoter'"
        ) { it.getString(1) }
}
