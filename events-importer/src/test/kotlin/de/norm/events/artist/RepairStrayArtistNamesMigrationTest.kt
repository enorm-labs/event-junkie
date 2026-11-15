package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.scraper.isNonArtistName
import de.norm.events.slug.SlugGenerator
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V253 against planted rows on a database migrated to before it (#5136). */
class RepairStrayArtistNamesMigrationTest {
    @Test
    fun `the new name keeps the slug and the dropped name is no act`() {
        assertSoftly {
            artistSlugFor("Stevie Cox") shouldBe "stevie-cox"
            SlugGenerator.slugify("DJS") shouldBe "djs"
            isNonArtistName("DJS") shouldBe true
        }
    }

    @Test
    fun `renames the dashed row and drops the heading Renate alone bills`() {
        migrated(
            artists = listOf("Stevie Cox -" to "stevie-cox", "Ben Sims" to "ben-sims", "DJS" to "djs", "Kirk" to "kirk"),
            billings = mapOf("tresor:all-night-long" to listOf("ben-sims", "stevie-cox"), "renate:974" to listOf("djs", "kirk"))
        ) { connection ->
            assertSoftly {
                name(connection, "stevie-cox") shouldBe "Stevie Cox"
                billing(connection, "tresor:all-night-long") shouldContainExactly listOf("ben-sims", "stevie-cox")
                billing(connection, "renate:974") shouldContainExactly listOf("kirk")
                slugs(connection) shouldContainExactlyInAnyOrder listOf("stevie-cox", "ben-sims", "kirk")
                rows(connection, "SELECT count(*) FROM events.event") { it.getInt(1) }.single() shouldBe 2
            }
        }
    }

    @Test
    fun `leaves a row renamed by hand and a heading another venue bills too`() {
        migrated(
            artists = listOf("Stevie Cox" to "stevie-cox", "DJs" to "djs"),
            billings = mapOf("renate:974" to listOf("djs"), "lux:night" to listOf("djs", "stevie-cox"))
        ) { connection ->
            assertSoftly {
                name(connection, "stevie-cox") shouldBe "Stevie Cox"
                billing(connection, "renate:974") shouldContainExactly listOf("djs")
                billing(connection, "lux:night") shouldContainExactly listOf("djs", "stevie-cox")
            }
        }
    }

    @Test
    fun `changes nothing on a database without the rows`() {
        migrated(artists = listOf("Stevie Cox Jr." to "stevie-cox-jr"), billings = emptyMap()) { connection ->
            name(connection, "stevie-cox-jr") shouldBe "Stevie Cox Jr."
        }
    }

    private fun migrated(
        artists: List<Pair<String, String>>,
        billings: Map<String, List<String>>,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "252").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in artists) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    statement.execute(
                        "UPDATE artist SET musicbrainz_match = 'EXACT', musicbrainz_id = '00000000-0000-0000-0000-000000000001' WHERE slug = 'djs'"
                    )
                    for ((sourceId, slugs) in billings) {
                        statement.execute(
                            "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, 'Fixture night', " +
                                "'${SlugGenerator.slugify(sourceId)}', DATE '2026-11-01', '$sourceId' FROM venue"
                        )
                        slugs.forEachIndexed { order, slug ->
                            statement.execute(
                                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                                    "SELECT e.id, a.id, 'DJ', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
                            )
                        }
                    }
                }
                flyway(postgres, "253").migrate()
                check(connection)
            }
        }
    }

    private fun billing(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) }

    private fun name(
        connection: Connection,
        slug: String
    ): String = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }
}
