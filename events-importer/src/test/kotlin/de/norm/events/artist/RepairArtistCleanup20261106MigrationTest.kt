package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.scraper.stripLineupBracket
import de.norm.events.scraper.urbanspree.urbanSpreeActName
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

/** Runs V229 against planted rows on a database migrated to before it. */
class RepairArtistCleanup20261106MigrationTest {
    private val migration = Files.readString(MIGRATION)

    @Test
    fun `every slug in the file is the one the stored name or the importer derives`() {
        val merges = MERGE_ROW.findAll(migration).map { it.groupValues }.toList()
        merges.size shouldBe 4
        val drops = DROP_ROW.findAll(migration).map { it.groupValues }.toList()
        drops.size shouldBe 2
        assertSoftly {
            merges.forEach { row ->
                val sourceId = if (row[LOSER] == "v-opera-live") OHM_NIGHT else null
                SlugGenerator.slugify(row[LOSER_NAME]) shouldBe row[LOSER]
                artistSlugFor(stripLineupBracket(urbanSpreeActName(row[LOSER_NAME])), sourceId) shouldBe row[SURVIVOR]
                artistSlugFor(row[SURVIVOR_NAME], sourceId) shouldBe row[SURVIVOR]
            }
            drops.forEach { row -> SlugGenerator.slugify(row[DROP_NAME]) shouldBe row[DROP_SLUG] }
        }
    }

    @Test
    fun `merges the pairs, renames the act, drops the two rows and keeps a real act`() {
        migrated(
            artists = NAMES,
            billings =
                mapOf(
                    "wild_at_heart:aftershow" to listOf("dj-louie-wray"),
                    "wild_at_heart:both" to listOf("dj-louie-wray", "louie-wray"),
                    "kitkatclub:night" to listOf("dj-paypaul"),
                    "weekend:night" to listOf("paypaul"),
                    "aeden:night" to listOf("panamamama"),
                    FRIBERTY_NIGHT to listOf(RELEASE_SLUG, "kiki-bohemia"),
                    OHM_NIGHT to listOf("v-opera-live", "support-act"),
                    "schokoladen:e20260904" to listOf("v", "support-act"),
                    "monarch:leaked" to listOf("leaked-session-04"),
                    "renate:19-years" to listOf("whacking-activation-w", "session-victim")
                )
        ) { connection ->
            assertSoftly {
                billing(connection, "wild_at_heart:aftershow") shouldContainExactly listOf("louie-wray")
                billing(connection, "wild_at_heart:both") shouldContainExactly listOf("louie-wray")
                // The pair the operator kept apart stays two rows.
                billing(connection, "kitkatclub:night") shouldContainExactly listOf("dj-paypaul")
                billing(connection, "weekend:night") shouldContainExactly listOf("paypaul")
                billing(connection, "aeden:night") shouldContainExactly listOf("panama-mama")
                billing(connection, FRIBERTY_NIGHT) shouldContainExactly listOf("friberty", "kiki-bohemia")
                billing(connection, OHM_NIGHT) shouldContainExactly listOf("v-2", "support-act")
                name(connection, "v-2") shouldBe "V."
                billing(connection, "schokoladen:e20260904") shouldContainExactly listOf("v", "support-act")
                name(connection, "v") shouldBe "V"
                aliases(connection, "v-2") shouldContainExactly listOf("v-opera-live")
                aliases(connection, "v") shouldBe emptyList()
                billing(connection, "monarch:leaked") shouldBe emptyList()
                billing(connection, "renate:19-years") shouldContainExactly listOf("session-victim")
                name(connection, "friberty") shouldBe "Friberty"
                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf("louie-wray", "dj-paypaul", "paypaul", "panama-mama", "friberty", "kiki-bohemia", "session-victim", "v", "v-2", "support-act")
                follows(connection) shouldContainExactly listOf("panama-mama")
                aliases(connection, "panama-mama") shouldContainExactly listOf("panamamama")
                aliases(connection, "friberty") shouldContainExactly listOf(RELEASE_SLUG)
            }
        }
    }

    @Test
    fun `leaves a renamed row, a missing survivor and a row another source bills alone, and reuses Friberty`() {
        migrated(
            artists =
                listOf(
                    "DJ Louie Wray" to "dj-louie-wray",
                    "Panamamama (live)" to "panamamama",
                    "Panama Mama" to "panama-mama",
                    RELEASE_NAME to RELEASE_SLUG,
                    "FRIBERTY" to "friberty",
                    "Leaked Session 04 (live)" to "leaked-session-04",
                    "Whacking Activation w/" to "whacking-activation-w",
                    "V. (Opera)" to "v-opera-live"
                ),
            billings =
                mapOf(
                    "wild_at_heart:aftershow" to listOf("dj-louie-wray"),
                    "aeden:night" to listOf("panamamama"),
                    FRIBERTY_NIGHT to listOf(RELEASE_SLUG),
                    OHM_NIGHT to listOf("v-opera-live"),
                    "monarch:leaked" to listOf("leaked-session-04"),
                    "so36:night" to listOf("whacking-activation-w")
                )
        ) { connection ->
            assertSoftly {
                billing(connection, "wild_at_heart:aftershow") shouldContainExactly listOf("dj-louie-wray")
                billing(connection, "aeden:night") shouldContainExactly listOf("panamamama")
                billing(connection, FRIBERTY_NIGHT) shouldContainExactly listOf("friberty")
                name(connection, "friberty") shouldBe "FRIBERTY"
                billing(connection, "monarch:leaked") shouldContainExactly listOf("leaked-session-04")
                billing(connection, "so36:night") shouldContainExactly listOf("whacking-activation-w")
                billing(connection, OHM_NIGHT) shouldContainExactly listOf("v-opera-live")
                slugs(connection).contains("v-2") shouldBe false
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
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "228").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in artists) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    for ((sourceId, slugs) in billings) {
                        statement.execute(
                            "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, 'Fixture night', " +
                                "'${SlugGenerator.slugify(sourceId)}', DATE '2026-11-06', '$sourceId' FROM venue"
                        )
                        slugs.forEachIndexed { order, slug ->
                            statement.execute(
                                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                                    "SELECT e.id, a.id, 'DJ', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
                            )
                        }
                    }
                    if (artists == NAMES) {
                        statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('fan@example.de', 'de')")
                        statement.execute(
                            "INSERT INTO accounts.follow (account_id, kind, entity_id) " +
                                "SELECT ac.id, 'artist', a.id FROM accounts.account ac, artist a WHERE a.slug = 'panamamama'"
                        )
                    }
                }
                flyway(postgres, "229").migrate()
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

    private fun follows(connection: Connection): List<String> =
        rows(connection, "SELECT a.slug FROM accounts.follow f JOIN events.artist a ON a.id = f.entity_id WHERE f.kind = 'artist'") { it.getString(1) }

    private fun aliases(
        connection: Connection,
        slug: String
    ): List<String> =
        rows(
            connection,
            "SELECT alias.slug FROM events.artist_slug_alias alias JOIN events.artist a ON a.id = alias.artist_id WHERE a.slug = '$slug'"
        ) { it.getString(1) }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V229__repair_artist_cleanup_2026_11_06.sql")

        /** One `(loser, loser_name, survivor, survivor_name)` row of the merge list. */
        val MERGE_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'\)[,;]""", RegexOption.MULTILINE)

        /** One `(slug, name, source_prefix)` row of the drop list; a three-column row has a prefix ending in `:`. */
        val DROP_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+)',\s*'[^']+:'\)[,;]""", RegexOption.MULTILINE)

        const val LOSER = 1
        const val LOSER_NAME = 2
        const val SURVIVOR = 3
        const val SURVIVOR_NAME = 4
        const val DROP_SLUG = 1
        const val DROP_NAME = 2
        const val OHM_NIGHT = "ohm:2026-09-24-la-casita"
        const val FRIBERTY_NIGHT = "urban_spree:concerts/friberty-releaseshow"
        const val RELEASE_NAME = "Friberty “Friberty I”-Releaseshow"
        const val RELEASE_SLUG = "friberty-friberty-i-releaseshow"
        val NAMES =
            listOf(
                "DJ Louie Wray" to "dj-louie-wray",
                "Louie Wray" to "louie-wray",
                "DJ PayPaul" to "dj-paypaul",
                "PayPaul" to "paypaul",
                "Panamamama" to "panamamama",
                "Panama Mama" to "panama-mama",
                RELEASE_NAME to RELEASE_SLUG,
                "Kiki Bohemia" to "kiki-bohemia",
                "Leaked Session 04" to "leaked-session-04",
                "Whacking Activation w/" to "whacking-activation-w",
                "Session Victim" to "session-victim",
                "V. (Opera live)" to "v-opera-live",
                "V" to "v",
                "Support Act" to "support-act"
            )
    }
}
