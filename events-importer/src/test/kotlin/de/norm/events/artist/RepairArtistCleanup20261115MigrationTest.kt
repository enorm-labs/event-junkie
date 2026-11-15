package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Runs V252 against planted rows on a database migrated to before it. */
class RepairArtistCleanup20261115MigrationTest {
    private val migration = Files.readString(MIGRATION)

    @Test
    fun `every slug in the file is the one the stored name or the importer derives`() {
        val replacements = REPLACE_ROW.findAll(migration).map { it.groupValues }.toList()
        replacements.size shouldBe 4
        val drops = DROP_ROW.findAll(migration).map { it.groupValues }.toList()
        drops.size shouldBe 1
        assertSoftly {
            replacements.forEach { row -> artistSlugFor(row[NEW_NAME]) shouldBe row[NEW_SLUG] }
            replacements.map { it[OLD_SLUG] }.toSet() shouldBe
                setOf(SlugGenerator.slugify(BEDO_AND_PATRON_NAME), SlugGenerator.slugify(REICHELT_BEI_WEIHSER_NAME))
            drops.forEach { row -> SlugGenerator.slugify(row[DROP_NAME]) shouldBe row[DROP_SLUG] }
        }
    }

    @Test
    fun `splits the two pairs in their place, drops the credit and keeps the acts' other nights`() {
        migrated(artists = NAMES, billings = BILLINGS, follows = listOf(BEDO_AND_PATRON)) { connection ->
            assertSoftly {
                lineup(connection, BI_NUU) shouldContainExactly listOf("bedo HEADLINER 0", "patron HEADLINER 1")
                lineup(connection, URANIA) shouldContainExactly
                    listOf("jens-balzer HEADLINER 0", "victoria-reichelt HEADLINER 1", "rabea-weihser HEADLINER 2")
                lineup(connection, KANTINE) shouldContainExactly
                    listOf("flowgeist HEADLINER 0", "alex-palmieri HEADLINER 2", "pareal HEADLINER 3")
                lineup(connection, "urania:wissenschaft") shouldContainExactly listOf("victoria-reichelt HEADLINER 0")
                lineup(connection, "urania:literatur") shouldContainExactly listOf("rabea-weihser HEADLINER 0")
                lineup(connection, "pfefferberg:lesung") shouldContainExactly listOf("rabea-weihser HEADLINER 0")
                name(connection, "bedo") shouldBe "Bedo"
                name(connection, "patron") shouldBe "Patron"
                name(connection, "victoria-reichelt") shouldBe "Victoria Reichelt"
                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf("bedo", "patron", "jens-balzer", "victoria-reichelt", "rabea-weihser", "flowgeist", "alex-palmieri", "pareal")
                follows(connection) shouldContainExactly listOf("bedo")
            }
        }
    }

    @Test
    fun `moves a later link down and leaves a row another source bills alone`() {
        migrated(
            artists = NAMES,
            billings =
                mapOf(
                    BI_NUU to listOf(BEDO_AND_PATRON, "pareal"),
                    KANTINE to listOf(CREDIT),
                    "so36:night" to listOf(CREDIT)
                )
        ) { connection ->
            assertSoftly {
                lineup(connection, BI_NUU) shouldContainExactly listOf("bedo HEADLINER 0", "patron HEADLINER 1", "pareal HEADLINER 2")
                lineup(connection, KANTINE) shouldContainExactly listOf("$CREDIT HEADLINER 0")
                lineup(connection, "so36:night") shouldContainExactly listOf("$CREDIT HEADLINER 0")
            }
        }
    }

    @Test
    fun `creates nothing on a database without the rows`() {
        migrated(artists = emptyList(), billings = emptyMap()) { connection ->
            slugs(connection) shouldBe emptyList()
        }
    }

    private fun migrated(
        artists: List<Pair<String, String>>,
        billings: Map<String, List<String>>,
        follows: List<String> = emptyList(),
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "251").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in artists) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    for ((sourceId, slugs) in billings) {
                        statement.execute(
                            "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, 'Fixture night', " +
                                "'${SlugGenerator.slugify(sourceId)}', DATE '2026-11-11', '$sourceId' FROM venue"
                        )
                        slugs.forEachIndexed { order, slug ->
                            statement.execute(
                                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                                    "SELECT e.id, a.id, 'HEADLINER', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
                            )
                        }
                    }
                    if (follows.isNotEmpty()) {
                        statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('fan@example.de', 'de')")
                        statement.execute(
                            "INSERT INTO accounts.follow (account_id, kind, entity_id) SELECT ac.id, 'artist', a.id " +
                                "FROM accounts.account ac, artist a WHERE a.slug IN (${follows.joinToString { "'$it'" }})"
                        )
                    }
                }
                flyway(postgres, "252").migrate()
                check(connection)
            }
        }
    }

    /** Each link of the night as `slug ROLE billing_order`, in billing order. */
    private fun lineup(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug || ' ' || ea.role || ' ' || ea.billing_order FROM events.event_artist ea " +
                "JOIN events.event e ON e.id = ea.event_id JOIN events.artist a ON a.id = ea.artist_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) }

    private fun name(
        connection: Connection,
        slug: String
    ): String = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun follows(connection: Connection): List<String> =
        rows(connection, "SELECT a.slug FROM accounts.follow f JOIN events.artist a ON a.id = f.entity_id WHERE f.kind = 'artist'") { it.getString(1) }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V252__repair_artist_cleanup_2026_11_15.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the replacement list. */
        val REPLACE_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+:)',\s*'([^']+)',\s*'([^']+)',\s*(\d+)\)[,;]""", RegexOption.MULTILINE)

        /** One `(slug, name, source_prefix)` row of the drop list; a drop's prefix ends in `:`. */
        val DROP_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+)',\s*'[^']+:'\)[,;]""", RegexOption.MULTILINE)

        const val OLD_SLUG = 1
        const val NEW_SLUG = 3
        const val NEW_NAME = 4
        const val DROP_SLUG = 1
        const val DROP_NAME = 2
        const val BEDO_AND_PATRON_NAME = "Bedo & Patron"
        const val REICHELT_BEI_WEIHSER_NAME = "Victoria Reichelt bei Rabea Weihser"
        const val BEDO_AND_PATRON = "bedo-patron"
        const val REICHELT_BEI_WEIHSER = "victoria-reichelt-bei-rabea-weihser"
        const val CREDIT = "4c-stefano-canavese"
        const val BI_NUU = "binuu:nlahevqk44ms4wa"
        const val URANIA = "urania:schoenheitssalon-wie-du-wieder-aussiehst"
        const val KANTINE = "kantine_am_berghain:82079"
        val BILLINGS =
            mapOf(
                BI_NUU to listOf(BEDO_AND_PATRON),
                URANIA to listOf("jens-balzer", REICHELT_BEI_WEIHSER),
                "urania:wissenschaft" to listOf("victoria-reichelt"),
                "urania:literatur" to listOf("rabea-weihser"),
                "pfefferberg:lesung" to listOf("rabea-weihser"),
                KANTINE to listOf("flowgeist", CREDIT, "alex-palmieri", "pareal")
            )
        val NAMES =
            listOf(
                BEDO_AND_PATRON_NAME to BEDO_AND_PATRON,
                "Jens Balzer" to "jens-balzer",
                REICHELT_BEI_WEIHSER_NAME to REICHELT_BEI_WEIHSER,
                "Victoria Reichelt" to "victoria-reichelt",
                "Rabea Weihser" to "rabea-weihser",
                "Flowgeist" to "flowgeist",
                "4C & Stefano Canavese" to CREDIT,
                "Alex Palmieri" to "alex-palmieri",
                "Pareal" to "pareal"
            )
    }
}
