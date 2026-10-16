package de.norm.events.artist

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V167 against planted rows on a database migrated to before it. */
class RepairArtistCleanup20261016MigrationTest {
    @Test
    fun `every replacement and merge slug is the one the importer derives from its name`() {
        val sql = Files.readString(MIGRATION)
        val replacements = REPLACEMENT_ROW.findAll(sql).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val merges = MERGE_ROW.findAll(sql).map { it.groupValues[1] to it.groupValues[2] }.toList()
        replacements.size shouldBe 16
        merges.size shouldBe 2
        (replacements + merges).forEach { (slug, name) -> artistSlugFor(name) shouldBe slug }
    }

    @Test
    fun `splits a row in place, renames onto a stored act, merges, drops and tidies, each on its own source`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "166").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                    plant(statement, CLASH, "bad-sound-system-feat-fermin-muguruza", "the-salsa-punk-orkestra")
                    plant(statement, DUNCKER, "smoboda", "dj-gomez")
                    plant(statement, CRACK, "spntaneous", "dj-don-rogall")
                    plant(statement, KATER, "unauffinbar")
                    // A same-slug row another venue bills keeps that link.
                    plant(statement, OTHER, "dj-gomez")
                }
                flyway(postgres, "167").migrate()

                billing(connection, CLASH) shouldBe listOf("bad-sound-system", "fermin-muguruza", "the-salsa-punk-orkestra")
                billing(connection, DUNCKER) shouldBe listOf("smoboda", "gomez")
                billing(connection, CRACK) shouldBe listOf("don-rogall")
                billing(connection, KATER) shouldBe listOf("unauffinbar")
                billing(connection, OTHER) shouldBe listOf("dj-gomez")
                rows(connection, "SELECT name FROM events.artist WHERE slug = 'unauffinbar'") { it.getString(1) } shouldBe listOf("Unauffinbar")
                slugs(connection) shouldContainExactlyInAnyOrder
                    listOf(
                        "bad-sound-system",
                        "fermin-muguruza",
                        "the-salsa-punk-orkestra",
                        "smoboda",
                        "gomez",
                        "dj-gomez",
                        "don-rogall",
                        "unauffinbar"
                    )
            }
        }
    }

    @Test
    fun `clears the English band's match and enrichment from the Duncker DJ gomez`() {
        val row = gomezAfterMigration(BAND_MBID)

        row["musicbrainz_match"] shouldBe "AMBIGUOUS"
        row["musicbrainz_checked_at"] shouldNotBe null
        row["name"] shouldBe "Gomez"
        row["discogs_match"] shouldBe "UNCHECKED"
        (ENRICHED_COLUMNS + "musicbrainz_id" + "musicbrainz_enriched_at" + "comedian" + "discogs_id" + "discogs_checked_at")
            .forEach { column -> withClue(column) { row[column] shouldBe null } }
    }

    @Test
    fun `leaves a gomez row matched to another MusicBrainz artist alone`() {
        val row = gomezAfterMigration(OTHER_MBID)

        row["musicbrainz_match"] shouldBe "EXACT"
        row["musicbrainz_id"] shouldBe OTHER_MBID
        ENRICHED_COLUMNS.forEach { column -> withClue(column) { row[column] shouldNotBe null } }
    }

    /** The `gomez` row after V167, planted before it as an enriched EXACT match to [mbid]. */
    private fun gomezAfterMigration(mbid: String): Map<String, Any?> =
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "166").migrate()
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO events.artist (name, slug, musicbrainz_id, musicbrainz_match, musicbrainz_checked_at, " +
                            "musicbrainz_enriched_at, comedian, artist_type, founded, founded_in, country, " +
                            "description, description_language, description_attribution, description_licence_id, description_source_url, " +
                            "description_alt, description_alt_language, description_alt_attribution, description_alt_licence_id, " +
                            "description_alt_source_url, image_url, image_attribution, image_licence_id, image_source_url, " +
                            "${LINK_COLUMNS.joinToString()}) VALUES ('Gomez', 'gomez', '$mbid', 'EXACT', now(), now(), false, " +
                            "'GROUP', '1996', 'Southport', 'GB', 'An English indie rock band.', 'en', 'Wikipedia', 'CC-BY-SA-4.0', " +
                            "'https://en.wikipedia.org/wiki/Gomez_(band)', 'Eine britische Band.', 'de', 'Wikipedia', 'CC-BY-SA-4.0', " +
                            "'https://de.wikipedia.org/wiki/Gomez_(Band)', 'https://upload.wikimedia.org/g.jpg', 'Wikimedia Commons', " +
                            "'CC-BY-2.0', 'https://commons.wikimedia.org/wiki/File:g.jpg', " +
                            LINK_COLUMNS.joinToString { "'https://example.org/$it'" } + ")"
                    )
                }
                flyway(postgres, "167").migrate()
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM events.artist WHERE slug = 'gomez'").use { rs ->
                        rs.next() shouldBe true
                        (1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to rs.getObject(it) }
                    }
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
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-09-26', '$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'DJ', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun flyway(
        postgres: PostgreSQLContainer,
        target: String
    ): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun billing(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun <T : Any> rows(
        connection: Connection,
        sql: String,
        read: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V167__repair_artist_cleanup_2026_10_16.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the replacement VALUES list. */
        val REPLACEMENT_ROW = Regex("""\('[^']+',\s*'[^']+',\s*'([^']+)',\s*'([^']+)',\s*\d+\)""")

        /** One `(loser, survivor, survivor_name)` row of the merge VALUES list. */
        val MERGE_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'([^']+)'\)""")

        const val BAND_MBID = "304cdd9d-a04e-4eb2-865c-980a553a0efd"
        const val OTHER_MBID = "00000000-0000-0000-0000-000000000001"
        val LINK_COLUMNS =
            listOf(
                "website_url",
                "facebook_url",
                "instagram_url",
                "youtube_url",
                "bandcamp_url",
                "soundcloud_url",
                "discogs_url",
                "wikidata_url",
                "resident_advisor_url",
                "spotify_url"
            )

        /** Every column the MusicBrainz enrichment writes (`ArtistEnrichmentStore`), all planted non-null. */
        val ENRICHED_COLUMNS =
            LINK_COLUMNS +
                listOf(
                    "artist_type",
                    "founded",
                    "founded_in",
                    "country",
                    "description",
                    "description_language",
                    "description_attribution",
                    "description_licence_id",
                    "description_source_url",
                    "description_alt",
                    "description_alt_language",
                    "description_alt_attribution",
                    "description_alt_licence_id",
                    "description_alt_source_url",
                    "image_url",
                    "image_attribution",
                    "image_licence_id",
                    "image_source_url"
                )
        const val CLASH = "clash:1209202619409"
        const val DUNCKER = "duncker:2026-11-07-independent-tanzmusik"
        const val CRACK = "crack_bellmer:the-sinners-saloon-e1071"
        const val KATER = "kater:1947"
        const val OTHER = "berghain:fixture-night"
        val ARTISTS =
            listOf(
                "Bad Sound System feat. Fermin Muguruza" to "bad-sound-system-feat-fermin-muguruza",
                "The SALSA PUNK Orkestra" to "the-salsa-punk-orkestra",
                "Smoboda" to "smoboda",
                "DJ Gomez" to "dj-gomez",
                "Gomez" to "gomez",
                "Spntaneous :)" to "spntaneous",
                "DJ Don Rogall" to "dj-don-rogall",
                "Don Rogall" to "don-rogall",
                "(Unauffinbar)" to "unauffinbar"
            )
    }
}
