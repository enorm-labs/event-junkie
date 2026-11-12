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

/** Runs V244 against planted rows on a database migrated to before it. */
class RepairArtistCleanup20261112MigrationTest {
    private val migration = Files.readString(MIGRATION)

    @Test
    fun `every slug in the file is the one the stored name or the importer derives`() {
        val replacements = REPLACE_ROW.findAll(migration).map { it.groupValues }.toList()
        replacements.size shouldBe 5
        val merges = MERGE_ROW.findAll(migration).map { it.groupValues }.toList()
        merges.size shouldBe 7
        val drops = DROP_ROW.findAll(migration).map { it.groupValues }.toList()
        drops.size shouldBe 2
        assertSoftly {
            replacements.forEach { row -> artistSlugFor(row[NEW_NAME]) shouldBe row[NEW_SLUG] }
            merges.forEach { row ->
                SlugGenerator.slugify(row[LOSER_NAME]) shouldBe row[LOSER]
                artistSlugFor(row[LOSER_NAME]) shouldBe row[SURVIVOR]
            }
            drops.forEach { row -> SlugGenerator.slugify(row[DROP_NAME]) shouldBe row[DROP_SLUG] }
        }
    }

    @Test
    fun `renames, splits, merges and drops the rows and keeps the pair the operator kept apart`() {
        migrated(
            artists = NAMES,
            billings = BILLINGS
        ) { connection ->
            assertSoftly {
                billing(connection, "void_club:human-made-007") shouldContainExactly listOf("primal-rhapsody", "rabbi-green")
                name(connection, "primal-rhapsody") shouldBe "Primal Rhapsody"
                billing(connection, "eight_mm:warm-up") shouldContainExactly listOf("wedding", "lyzza")
                billing(connection, "eight_mm:dj-set") shouldContainExactly listOf("wedding")
                billing(connection, "aeden:winter-date") shouldContainExactly listOf("molly")
                name(connection, "molly") shouldBe "mølly"
                billing(connection, "kantine_am_berghain:82506") shouldContainExactly
                    listOf("radwan-ghazi-moumneh", "frederic-d-oberland", "jessica-moss")
                name(connection, "frederic-d-oberland") shouldBe "Frédéric D. Oberland"
                billing(connection, "soda:sodalicious") shouldContainExactly listOf("defjam", "bat", "selekta-mik", "dj-rokit")
                billing(connection, "weekend:80s") shouldContainExactly listOf("bat", "rokit")
                billing(connection, "renate:halloween") shouldContainExactly listOf("angelic-sintesis")
                billing(connection, "hafenbar:schlager") shouldContainExactly listOf("dj-haukepauke")
                billing(connection, "gretchen:3579") shouldContainExactly listOf("high-score")
                billing(connection, "renate:our-house") shouldContainExactly listOf("hityawitthat")
                billing(connection, "ritter_butzke:hippie-new-year") shouldContainExactly listOf("kirk")
                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf(
                        "primal-rhapsody",
                        "rabbi-green",
                        "wedding",
                        "lyzza",
                        "molly",
                        "radwan-ghazi-moumneh",
                        "frederic-d-oberland",
                        "jessica-moss",
                        "defjam",
                        "bat",
                        "selekta-mik",
                        "dj-rokit",
                        "rokit",
                        "angelic-sintesis",
                        "dj-haukepauke",
                        "high-score",
                        "hityawitthat",
                        "kirk"
                    )
                follows(connection) shouldContainExactlyInAnyOrder listOf("defjam", "molly")
                aliases(connection, "defjam") shouldContainExactly listOf("dj-defjam")
                aliases(connection, "rabbi-green") shouldContainExactly listOf("rabbigreen")
            }
        }
    }

    @Test
    fun `leaves a row renamed by hand, a missing survivor and a row another source bills alone`() {
        migrated(
            artists =
                listOf(
                    "DJ Bat (live)" to "dj-bat",
                    "Bat" to "bat",
                    "DJ DefJam" to "dj-defjam",
                    "Wedding (CPN)" to "wedding-cpn",
                    "Floor Hosting By Sachsentrance" to "floor-hosting-by-sachsentrance"
                ),
            billings =
                mapOf(
                    "soda:sodalicious" to listOf("dj-bat", "dj-defjam"),
                    "acud:night" to listOf("wedding-cpn"),
                    "so36:night" to listOf("floor-hosting-by-sachsentrance")
                )
        ) { connection ->
            assertSoftly {
                billing(connection, "soda:sodalicious") shouldContainExactly listOf("dj-bat", "dj-defjam")
                billing(connection, "acud:night") shouldContainExactly listOf("wedding-cpn")
                billing(connection, "so36:night") shouldContainExactly listOf("floor-hosting-by-sachsentrance")
                slugs(connection).contains("wedding") shouldBe false
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
                flyway(postgres, "243").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in artists) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    for ((sourceId, slugs) in billings) {
                        statement.execute(
                            "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, 'Fixture night', " +
                                "'${SlugGenerator.slugify(sourceId)}', DATE '2026-11-12', '$sourceId' FROM venue"
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
                                "SELECT ac.id, 'artist', a.id FROM accounts.account ac, artist a WHERE a.slug IN ('dj-defjam', 'molly-on-molly')"
                        )
                    }
                }
                flyway(postgres, "244").migrate()
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
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V244__repair_artist_cleanup_2026_11_12.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the replacement list. */
        val REPLACE_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+:)',\s*'([^']+)',\s*'([^']+)',\s*(\d+)\)[,;]""", RegexOption.MULTILINE)

        /** One `(loser, loser_name, survivor)` row of the merge list; a survivor never ends in `:`. */
        val MERGE_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+)',\s*'([^':]+)'\)[,;]""", RegexOption.MULTILINE)

        /** One `(slug, name, source_prefix)` row of the drop list; a drop's prefix ends in `:`. */
        val DROP_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+)',\s*'[^']+:'\)[,;]""", RegexOption.MULTILINE)

        const val NEW_SLUG = 3
        const val NEW_NAME = 4
        const val LOSER = 1
        const val LOSER_NAME = 2
        const val SURVIVOR = 3
        const val DROP_SLUG = 1
        const val DROP_NAME = 2
        const val RADWAN_AND_OBERLAND = "radwan-ghazi-moumneh-frederic-d-oberland"
        const val SIGN_UP = "sign-up-here-https-forms-gle-yrdr1dz7cifgr7jc9"
        val BILLINGS =
            mapOf(
                "void_club:human-made-007" to listOf("primal-rhapsody-dahraxt", "rabbigreen"),
                "void_club:human-made-006" to listOf("rabbi-green"),
                "eight_mm:warm-up" to listOf("wedding-cpn", "lyzza"),
                "eight_mm:dj-set" to listOf("wedding"),
                "aeden:winter-date" to listOf("molly-on-molly"),
                "kantine_am_berghain:82506" to listOf(RADWAN_AND_OBERLAND, "jessica-moss"),
                "soda:sodalicious" to listOf("dj-defjam", "dj-bat", "dj-selekta-mik", "dj-rokit"),
                "soda:x-mas" to listOf("defjam", "selekta-mik"),
                "weekend:80s" to listOf("bat", "rokit"),
                "renate:halloween" to listOf("dj-angelic-sintesis"),
                "hafenbar:schlager" to listOf("dj-hauke-pauke"),
                "hafenbar:kaptains" to listOf("dj-haukepauke"),
                "gretchen:3579" to listOf("dj-high-score", "high-score"),
                "eschschloraque:post-punk" to listOf("angelic-sintesis"),
                "renate:our-house" to listOf(SIGN_UP, "hityawitthat"),
                "ritter_butzke:hippie-new-year" to listOf("floor-hosting-by-sachsentrance", "kirk")
            )
        val NAMES =
            listOf(
                "Primal Rhapsody (DAHRAXT)" to "primal-rhapsody-dahraxt",
                "RabbiGreen" to "rabbigreen",
                "Rabbi Green" to "rabbi-green",
                "Wedding (CPN)" to "wedding-cpn",
                "Wedding" to "wedding",
                "LYZZA" to "lyzza",
                "mølly (on molly)" to "molly-on-molly",
                "Radwan Ghazi Moumneh & Frédéric D. Oberland" to RADWAN_AND_OBERLAND,
                "Radwan Ghazi Moumneh" to "radwan-ghazi-moumneh",
                "Jessica Moss" to "jessica-moss",
                "DJ DefJam" to "dj-defjam",
                "Defjam" to "defjam",
                "DJ Bat" to "dj-bat",
                "Bat" to "bat",
                "DJ Selekta Mik" to "dj-selekta-mik",
                "Selekta Mik" to "selekta-mik",
                "Dj Rokit" to "dj-rokit",
                "Rokit" to "rokit",
                "DJ Angelic Sintesis" to "dj-angelic-sintesis",
                "Angelic Sintesis" to "angelic-sintesis",
                "DJ Hauke_Pauke" to "dj-hauke-pauke",
                "DJ HaukePauke" to "dj-haukepauke",
                "DJ High Score" to "dj-high-score",
                "High Score" to "high-score",
                "Sign Up Here: https://forms.gle/yRDr1DZ7ciFGr7Jc9" to SIGN_UP,
                "Hityawitthat" to "hityawitthat",
                "Floor Hosting By Sachsentrance" to "floor-hosting-by-sachsentrance",
                "Kirk" to "kirk"
            )
    }
}
