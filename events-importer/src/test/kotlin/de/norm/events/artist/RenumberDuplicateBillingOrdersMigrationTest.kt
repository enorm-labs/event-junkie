package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V254 against planted rows on a database migrated to before it (#5137). */
class RenumberDuplicateBillingOrdersMigrationTest {
    @Test
    fun `renumbers an event whose order repeats and keeps a split act next to its partner`() {
        migrated(
            // Inserted in id order: the original links first, then the two acts V224 split the fused row into.
            links =
                listOf(
                    Link("golden_gate:11839", "javier-anxiety", 3),
                    Link("golden_gate:11839", "gingerblack", 2),
                    Link("golden_gate:11839", "sharhok-dini", 3),
                    Link("renate:night", "kirk", 0),
                    Link("renate:night", "lyzza", 0),
                    Link("renate:night", "rokit", 4)
                )
        ) { connection ->
            assertSoftly {
                lineup(connection, "golden_gate:11839") shouldContainExactly
                    listOf("gingerblack" to 0, "sharhok-dini" to 1, "javier-anxiety" to 2)
                lineup(connection, "renate:night") shouldContainExactly listOf("lyzza" to 0, "kirk" to 1, "rokit" to 2)
            }
        }
    }

    @Test
    fun `leaves an event with no repeated order alone, gaps included`() {
        migrated(
            links =
                listOf(
                    Link("tresor:night", "ben-sims", 1),
                    Link("tresor:night", "stevie-cox", 3),
                    Link("golden_gate:other", "gingerblack", 0),
                    Link("golden_gate:other", "sharhok-dini", 0)
                )
        ) { connection ->
            assertSoftly {
                lineup(connection, "tresor:night") shouldContainExactly listOf("ben-sims" to 1, "stevie-cox" to 3)
                lineup(connection, "golden_gate:other") shouldContainExactly listOf("sharhok-dini" to 0, "gingerblack" to 1)
            }
        }
    }

    private data class Link(
        val sourceId: String,
        val artistSlug: String,
        val billingOrder: Int
    )

    private fun migrated(
        links: List<Link>,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "252").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for (slug in links.map { it.artistSlug }.distinct()) statement.execute("INSERT INTO artist (name, slug) VALUES ('$slug', '$slug')")
                    for (sourceId in links.map { it.sourceId }.distinct()) {
                        statement.execute(
                            "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, 'Fixture night', " +
                                "'${SlugGenerator.slugify(sourceId)}', DATE '2026-10-01', '$sourceId' FROM venue"
                        )
                    }
                    for (link in links) {
                        statement.execute(
                            "INSERT INTO event_artist (event_id, artist_id, role, billing_order) SELECT e.id, a.id, 'DJ', ${link.billingOrder} " +
                                "FROM event e, artist a WHERE e.source_id = '${link.sourceId}' AND a.slug = '${link.artistSlug}'"
                        )
                    }
                }
                flyway(postgres, "254").migrate()
                check(connection)
            }
        }
    }

    /** The lineup of [sourceId] as `(slug, billing order)`, in billing order; a tie is broken by the newer row first. */
    private fun lineup(
        connection: Connection,
        sourceId: String
    ): List<Pair<String, Int>> =
        rows(
            connection,
            "SELECT a.slug, ea.billing_order FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order, ea.id DESC"
        ) { it.getString(1) to it.getInt(2) }
}
