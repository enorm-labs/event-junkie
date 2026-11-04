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

/** Runs V225 against planted rows on a database migrated to before it. */
class RepairArtistCleanupMergesMigrationTest {
    private val migration = Files.readString(MIGRATION)

    @Test
    fun `every merge loser's name now resolves to its survivor`() {
        val merges = MERGE_ROW.findAll(migration).map { row -> row.groupValues.map { it.replace("''", "'") } }.toList()
        merges.size shouldBe 11
        assertSoftly {
            merges.forEach { row ->
                artistSlugFor(row[LOSER_NAME]) shouldBe row[SURVIVOR]
                artistSlugFor(row[SURVIVOR_NAME]) shouldBe row[SURVIVOR]
                canonicalArtistName(row[LOSER_NAME]) shouldBe row[SURVIVOR_NAME]
            }
        }
    }

    @Test
    fun `merges the planted pairs and leaves a pair with a missing or renamed loser alone`() {
        migrated { connection ->
            assertSoftly {
                billing(connection, MADAME_CLAUDE) shouldContainExactly listOf("oberst-panizza")
                // A night that billed both rows keeps one link, at the survivor's place.
                billing(connection, BI_NUU) shouldContainExactly listOf("oberst-panizza", "support-act")
                // A pair whose survivor is missing, as on staging, is left alone.
                billing(connection, HAFENBAR) shouldContainExactly listOf("dj-lars-vegas")
                billing(connection, ALTE_KANTINE) shouldContainExactly listOf("manu-beatz")
                billing(connection, GOLDEN_GATE) shouldContainExactly listOf("rif-raf")
                // A loser renamed by hand since is left as it is.
                billing(connection, CAFE_KOEPENICK) shouldContainExactly listOf("muffcase")

                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf("oberst-panizza", "support-act", "dj-lars-vegas", "manu-beatz", "jonas-poppe", "testbild", "rif-raf", "muffcase", "muff-case")
                follows(connection, FAN) shouldContainExactlyInAnyOrder listOf("testbild", "rif-raf")
                follows(connection, COMPLETIST) shouldContainExactly listOf("testbild")
                aliases(connection, "rif-raf") shouldContainExactlyInAnyOrder listOf("rifraf", "rif-raf-old")
                aliases(connection, "testbild") shouldContainExactly listOf("dj-testbild")
                aliases(connection, "jonas-poppe") shouldBe emptyList()
            }
        }
    }

    @Test
    fun `renames MANU BEATZ and clears the Canadian group's match from rif-raf`() {
        migrated { connection ->
            val manu =
                rows(connection, "SELECT name, name_pinned FROM events.artist WHERE slug = 'manu-beatz'") {
                    listOf(it.getString(1), it.getBoolean(2))
                }
            manu.single() shouldContainExactly listOf("Manu Beatz", true)
            val rifRaf =
                rows(
                    connection,
                    "SELECT musicbrainz_id, musicbrainz_match, country, discogs_url, musicbrainz_checked_at > now() - interval '1 hour' " +
                        "FROM events.artist WHERE slug = 'rif-raf'"
                ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getBoolean(5)) }
            rifRaf.single() shouldContainExactly listOf(null, "AMBIGUOUS", null, null, true)
        }
    }

    private fun migrated(check: (Connection) -> Unit) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "224").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    statement.execute(
                        "UPDATE artist SET musicbrainz_id = '9f220e7d-28d5-41db-8376-947964765fc5', musicbrainz_match = 'EXACT', " +
                            "musicbrainz_checked_at = now() - interval '1 day', country = 'CA', " +
                            "discogs_url = 'https://www.discogs.com/artist/1' WHERE slug = 'rif-raf'"
                    )
                    statement.execute("INSERT INTO artist_slug_alias (slug, artist_id) SELECT 'rif-raf-old', id FROM artist WHERE slug = 'rifraf'")
                    plant(statement, MADAME_CLAUDE, "dj-oberst-panizza")
                    plant(statement, BI_NUU, "dj-oberst-panizza", "oberst-panizza", "support-act")
                    plant(statement, HAFENBAR, "dj-lars-vegas")
                    plant(statement, ALTE_KANTINE, "dj-manu-beatz")
                    plant(statement, GOLDEN_GATE, "rifraf")
                    plant(statement, CAFE_KOEPENICK, "muffcase")
                    follow(statement, FAN, "dj-testbild", "rifraf")
                    follow(statement, COMPLETIST, "dj-testbild", "testbild")
                }
                flyway(postgres, "225").migrate()
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
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-11-04', '$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'DJ', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
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

    private fun aliases(
        connection: Connection,
        slug: String
    ): List<String> =
        rows(
            connection,
            "SELECT alias.slug FROM events.artist_slug_alias alias JOIN events.artist a ON a.id = alias.artist_id WHERE a.slug = '$slug'"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V225__merge_artist_cleanup_twins_2026_11_04.sql")

        /** One `(loser, loser_name, survivor, survivor_name)` row of the merge list; `''` is an escaped apostrophe. */
        val MERGE_ROW = Regex("""^\s*\('([^']+)',\s*'((?:[^']|'')+)',\s*'([^']+)',\s*'((?:[^']|'')+)'\)[,;]""", RegexOption.MULTILINE)

        const val LOSER_NAME = 2
        const val SURVIVOR = 3
        const val SURVIVOR_NAME = 4
        const val MADAME_CLAUDE = "madame_claude:wave"
        const val BI_NUU = "bi_nuu:wave_noir"
        const val HAFENBAR = "hafenbar:rocknroll"
        const val ALTE_KANTINE = "alte_kantine:party"
        const val GOLDEN_GATE = "golden_gate:night"
        const val CAFE_KOEPENICK = "cafe_koepenick:night"
        const val FAN = "fan@example.de"
        const val COMPLETIST = "completist@example.de"
        val ARTISTS =
            listOf(
                "DJ Oberst Panizza" to "dj-oberst-panizza",
                "Oberst Panizza" to "oberst-panizza",
                "Support Act" to "support-act",
                "DJ Lars Vegas" to "dj-lars-vegas",
                "DJ Manu Beatz" to "dj-manu-beatz",
                "MANU BEATZ" to "manu-beatz",
                "Jonas Poppe" to "jonas-poppe",
                "DJ Testbild" to "dj-testbild",
                "Testbild" to "testbild",
                "RifRaf" to "rifraf",
                "Rif Raf" to "rif-raf",
                "Muffcase (live)" to "muffcase",
                "Muff Case" to "muff-case"
            )
    }
}
