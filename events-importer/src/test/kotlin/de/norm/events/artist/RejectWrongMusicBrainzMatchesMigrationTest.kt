package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V271 against planted rows on a database migrated to before it. */
class RejectWrongMusicBrainzMatchesMigrationTest {
    @Test
    fun `clears the band's match from wilco, keeps the venue's text, and rejects both bands`() {
        migrated(wilco = BAND_WILCO) { connection ->
            rows(
                connection,
                "SELECT musicbrainz_match, musicbrainz_id, description, instagram_url, image_url, artist_type, discogs_match " +
                    "FROM events.artist WHERE slug = 'wilco'"
            ) { rs -> (1..7).map { rs.getString(it) } }.single() shouldContainExactly
                listOf("AMBIGUOUS", null, "Wilco legt im Loungefloor auf.", null, null, null, "UNCHECKED")
            rejections(connection) shouldContainExactly listOf("gomez" to BAND_GOMEZ, "wilco" to BAND_WILCO)
        }
    }

    @Test
    fun `leaves a wilco row matched to another id alone`() {
        migrated(wilco = OTHER_MBID) { connection ->
            rows(connection, "SELECT musicbrainz_match, musicbrainz_id, instagram_url FROM events.artist WHERE slug = 'wilco'") { rs ->
                Triple(rs.getString(1), rs.getString(2), rs.getString(3))
            }.single() shouldBe Triple("EXACT", OTHER_MBID, "https://www.instagram.com/wilco/")
            rejections(connection) shouldContainExactly listOf("gomez" to BAND_GOMEZ)
        }
    }

    /**
     * Plants `gomez` as V167 left it and `wilco` matched EXACT to [wilco], with a venue's own text and the band's link, type
     * and Commons photo. Then migrates V271 and hands [check] the connection.
     */
    private fun migrated(
        wilco: String,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "270").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO events.artist (name, slug, musicbrainz_match) VALUES ('Gomez', 'gomez', 'AMBIGUOUS')")
                    statement.execute(
                        "INSERT INTO events.artist (name, slug, description, musicbrainz_match, musicbrainz_id, musicbrainz_checked_at, " +
                            "musicbrainz_enriched_at, instagram_url, artist_type, image_url, image_attribution, image_licence_id, image_source_url) " +
                            "VALUES ('Wilco', 'wilco', 'Wilco legt im Loungefloor auf.', 'EXACT', '$wilco', now(), now(), " +
                            "'https://www.instagram.com/wilco/', 'GROUP', 'https://upload.wikimedia.org/wilco.jpg', " +
                            "'Austin Nelson, via Wikimedia Commons', 'CC-BY-SA-4.0', 'https://commons.wikimedia.org/wiki/File:Wilco.jpg')"
                    )
                }
                flyway(postgres, "271").migrate()
                check(connection)
            }
        }
    }

    private fun rejections(connection: Connection): List<Pair<String, String>> =
        rows(
            connection,
            "SELECT a.slug, r.musicbrainz_id FROM events.artist_musicbrainz_rejection r JOIN events.artist a ON a.id = r.artist_id ORDER BY a.slug"
        ) { it.getString(1) to it.getString(2) }

    private companion object {
        const val BAND_WILCO = "9e53f84d-ef44-4c16-9677-5fd4d78cbd7d"
        const val BAND_GOMEZ = "304cdd9d-a04e-4eb2-865c-980a553a0efd"
        const val OTHER_MBID = "06e3bce0-c612-4a5f-b095-9ffed1e4a656"
    }
}
