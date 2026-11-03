package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
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
import java.sql.Statement

/** Runs V222 against planted rows on a database migrated to before it. */
class RepairArtistCleanup20261103MigrationTest {
    private val migration = Files.readString(MIGRATION)

    @Test
    fun `every slug is the one the importer derives from the new name`() {
        val displayNames = DISPLAY_ROW.findAll(migration).map { it.groupValues[1] to it.groupValues[3] }.toList()
        val replacements = REPLACEMENT_ROW.findAll(migration).map { it.groupValues[1] to it.groupValues[2] }.toList()
        displayNames.size shouldBe 27
        replacements.size shouldBe 2
        assertSoftly {
            (displayNames + replacements).forEach { (slug, name) -> artistSlugFor(name) shouldBe slug }
        }
    }

    @Test
    fun `every merge loser's name now resolves to its survivor`() {
        val merges = MERGE_ROW.findAll(migration).map { it.groupValues }.toList()
        merges.size shouldBe 5
        assertSoftly {
            merges.forEach { row ->
                artistSlugFor(row[LOSER_NAME]) shouldBe row[SURVIVOR]
                artistSlugFor(row[SURVIVOR_NAME]) shouldBe row[SURVIVOR]
            }
        }
    }

    @Test
    fun `renames, replaces, merges and drops the planted rows`() {
        migrated { connection ->
            assertSoftly {
                name(connection, "cctv") shouldBe "CCTV"
                name(connection, "rory") shouldBe "RØRY"
                // A name changed by hand since is left as it is.
                name(connection, "muna") shouldBe "Muna (LA)"
                pinned(connection, "cctv") shouldBe true
                pinned(connection, "muna") shouldBe false

                billing(connection, HUXLEYS) shouldContainExactly listOf("mr-hurley-die-pulveraffen")
                name(connection, "mr-hurley-die-pulveraffen") shouldBe "Mr. Hurley & Die Pulveraffen"
                billing(connection, ZUKUNFT_ONE) shouldContainExactly listOf("dxbxsx", "support-act")
                billing(connection, ZUKUNFT_TWO) shouldContainExactly listOf("dxbxsx")

                billing(connection, HASE) shouldContainExactly listOf("don-tom")
                // An event that billed both rows keeps one link, at the survivor's place.
                billing(connection, KITKAT) shouldContainExactly listOf("ladybird")
                billing(connection, TEMPODROM) shouldBe emptyList()
                // A same-slug row another venue bills keeps its row and its links (V149).
                billing(connection, FESTSAAL) shouldContainExactly listOf("anna")
                billing(connection, ELSEWHERE) shouldContainExactly listOf("anna")

                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf(
                        "cctv",
                        "rory",
                        "muna",
                        "what",
                        "mr-hurley-die-pulveraffen",
                        "dxbxsx",
                        "support-act",
                        "don-tom",
                        "ladybird",
                        "anna"
                    )
                follows(connection, FAN) shouldContainExactlyInAnyOrder listOf("don-tom", "dxbxsx")
                follows(connection, COMPLETIST) shouldContainExactly listOf("ladybird")
            }
        }
    }

    @Test
    fun `clears the drone duo's match from WHAT and keeps the lookup from asking again`() {
        migrated { connection ->
            val row =
                rows(
                    connection,
                    "SELECT name, name_pinned, musicbrainz_id, musicbrainz_match, description, discogs_url, " +
                        "name_changed_at > musicbrainz_checked_at FROM events.artist WHERE slug = 'what'"
                ) { listOf(it.getString(1), it.getBoolean(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getBoolean(7)) }
            row.single() shouldContainExactly listOf("WHAT", true, null, "AMBIGUOUS", null, null, false)
        }
    }

    private fun migrated(check: (Connection) -> Unit) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "221").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    statement.execute(
                        "UPDATE artist SET musicbrainz_id = '22ca4615-8d72-41dd-9c60-3e9628f0002a', musicbrainz_match = 'EXACT', " +
                            "musicbrainz_checked_at = now() - interval '1 day', description = 'A drone duo.', " +
                            "discogs_url = 'https://www.discogs.com/artist/1' WHERE slug = 'what'"
                    )
                    plant(statement, HUXLEYS, "mr-hurley-und-die-pulveraffen")
                    plant(statement, ZUKUNFT_ONE, "d-b-s", "support-act")
                    plant(statement, ZUKUNFT_TWO, "d-b-s")
                    plant(statement, HASE, "dontom")
                    plant(statement, KITKAT, "lady-bird", "ladybird")
                    plant(statement, TEMPODROM, "beat-it")
                    plant(statement, FESTSAAL, "anna")
                    plant(statement, ELSEWHERE, "anna")
                    follow(statement, FAN, "dontom", "d-b-s")
                    follow(statement, COMPLETIST, "lady-bird", "ladybird")
                }
                flyway(postgres, "222").migrate()
                check(connection)
            }
        }
    }

    private fun plant(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-11-03', '$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun follow(
        statement: Statement,
        email: String,
        vararg artistSlugs: String
    ) {
        statement.execute("INSERT INTO accounts.account (email, locale) VALUES ('$email', 'de')")
        artistSlugs.forEach { slug ->
            statement.execute(
                "INSERT INTO accounts.follow (account_id, kind, entity_id) " +
                    "SELECT ac.id, 'artist', a.id FROM accounts.account ac, artist a WHERE ac.email = '$email' AND a.slug = '$slug'"
            )
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

    private fun follows(
        connection: Connection,
        email: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug FROM accounts.follow f JOIN accounts.account ac ON ac.id = f.account_id " +
                "JOIN events.artist a ON a.id = f.entity_id WHERE ac.email = '$email' AND f.kind = 'artist'"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun name(
        connection: Connection,
        slug: String
    ): String = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private fun pinned(
        connection: Connection,
        slug: String
    ): Boolean = rows(connection, "SELECT name_pinned FROM events.artist WHERE slug = '$slug'") { it.getBoolean(1) }.single()

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V222__repair_artist_cleanup_2026_11_03.sql")

        /** One `(slug, old_name, name)` row of the display-name list. */
        val DISPLAY_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+)',\s*'([^']+)'\)[,;]""", RegexOption.MULTILINE)

        /** One `(old_slug, source_prefix, slug, name, position)` row of the replacement list. */
        val REPLACEMENT_ROW = Regex("""\('[^']+',\s*'[^']+',\s*'([^']+)',\s*'([^']+)',\s*\d\)""")

        /** One `(loser, loser_name, survivor, survivor_name)` row of the merge list. */
        val MERGE_ROW = Regex("""^\s*\('([^']+)',\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'\)[,;]""", RegexOption.MULTILINE)

        const val LOSER_NAME = 2
        const val SURVIVOR = 3
        const val SURVIVOR_NAME = 4
        const val HUXLEYS = "huxleys:hurley"
        const val ZUKUNFT_ONE = "neue_zukunft:one"
        const val ZUKUNFT_TWO = "neue_zukunft:two"
        const val HASE = "der_weisse_hase:techno"
        const val KITKAT = "kitkatclub:symbiotikka"
        const val TEMPODROM = "tempodrom:beat_it"
        const val FESTSAAL = "festsaal:anna"
        const val ELSEWHERE = "so36:anna"
        const val FAN = "fan@example.de"
        const val COMPLETIST = "completist@example.de"
        val ARTISTS =
            listOf(
                "Cctv" to "cctv",
                "Røry" to "rory",
                "Muna (LA)" to "muna",
                "What" to "what",
                "MR. Hurley Und Die Pulveraffen" to "mr-hurley-und-die-pulveraffen",
                "D*b*s" to "d-b-s",
                "Support Act" to "support-act",
                "DonTom" to "dontom",
                "Don Tom" to "don-tom",
                "Lady Bird" to "lady-bird",
                "LadyBird" to "ladybird",
                "Beat It!" to "beat-it",
                "Anna" to "anna"
            )
    }
}
