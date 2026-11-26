package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.connect
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/** Runs V286 against one row of each shape, and checks every slug in the file against [artistSlugFor]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairArtistCleanup20261126MigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        flyway(postgres, "283").migrate()
        connection = connect(postgres)
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            ARTISTS.forEach { (slug, name, instagram) ->
                val link = instagram?.let { "'$it'" } ?: "NULL"
                statement.execute("INSERT INTO artist (name, slug, instagram_url) VALUES ('$name', '$slug', $link)")
            }
            LINEUPS.entries.forEachIndexed { index, (sourceId, slugs) ->
                statement.execute(
                    "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                        "SELECT id, 'Night', 'night-$index', DATE '2026-12-09', '$sourceId' FROM venue"
                )
                slugs.forEachIndexed { order, slug ->
                    statement.execute(
                        "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) SELECT e.id, a.id, 'DJ', $order, FALSE " +
                            "FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
                    )
                }
            }
        }
        flyway(postgres, "286").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `a role label leaves the act's name, and the act billed beside it stays`() {
        lineup("schokoladen:e20261205") shouldContainExactly listOf("Kingston Headroll|kingston-headroll|0", "Berlitonian|berlitonian|1")
    }

    @Test
    fun `a band split on its ampersand becomes one act in the first half's place, and another venue's act of the half's name stays`() {
        lineup("maschinenhaus:-P7BwhOs7rAs5MCXOsTD") shouldContainExactly listOf("Thunderbolt & Lightning|thunderbolt-lightning|0")
        lineup("lido:night") shouldContainExactly listOf("Lightning|lightning|0")
        slugs() shouldNotContain "queen-thunderbolt"
    }

    @Test
    fun `a hand-made row loses its origin tag`() {
        lineup("manual:oxi:2026-12-19-silikon") shouldContainExactly listOf("Two Dots|two-dots|0")
        slugs() shouldNotContain "two-dots-fr"
    }

    @Test
    fun `the techno DJ takes a row of their own, and the singer keeps hers`() {
        lineup("kesselhaus:jas") shouldContainExactly listOf("JAS|jas|0")
        lineup("manual:sensorium:2026-12-09-techno-mittwoch") shouldContainExactly listOf("Grippe|grippe|0", "JAS|jas-2|1")
    }

    @Test
    fun `a merge moves the loser's links and leaves its slug as an alias`() {
        lineup("badehaus:jugo-uergens") shouldContainExactly listOf("Jugo Ürdens|jugo-urdens|0")
        rows(connection, "SELECT a.slug FROM events.artist_slug_alias s JOIN events.artist a ON a.id = s.artist_id WHERE s.slug = 'jugo-urgens'") {
            it.getString(1)
        } shouldContainExactly listOf("jugo-urdens")
    }

    @Test
    fun `a merge fills the survivor's empty profile link and keeps the one it holds`() {
        lineup("manual:suss-war-gestern:2026-12-05-the-unknown") shouldContainExactly listOf("Lobo|lobo|0")
        instagram("lobo") shouldBe "https://www.instagram.com/lobo-masked/"
        instagram("jugo-urdens") shouldBe "https://www.instagram.com/jugo-masked/"
    }

    @Test
    fun `a blurb line, a moderator and two unbilled rows go, and a billed act of a near name stays`() {
        lineup("soda:x-mas") shouldContainExactly listOf("Defjam|defjam|0")
        lineup("urania:talk").shouldContainExactly(emptyList())
        val slugs = slugs()
        listOf("15", "jochen-muller", "wird", "flightmode").forEach { slugs shouldNotContain it }
        slugs shouldContain "flight-mode"
    }

    @Test
    fun `every slug the file writes or matches is the slug of its name`() {
        val sql =
            javaClass
                .getResource("/db/migration/V286__repair_artist_cleanup_2026_11_26.sql")!!
                .readText()
                .lines()
        val renames = sql.mapNotNull { RENAME.matchEntire(it) }
        renames.size shouldBeGreaterThan 0
        assertSoftly {
            renames.forEach { withClue(it.value) { artistSlugFor(it.groupValues[2]) shouldBe it.groupValues[1] } }
        }
        val merges = sql.mapNotNull { MERGE.matchEntire(it) }
        merges.size shouldBeGreaterThan 0
        assertSoftly {
            merges.forEach { withClue(it.value) { artistSlugFor(HAND_MERGES[it.groupValues[1]] ?: it.groupValues[1]) shouldBe it.groupValues[2] } }
        }
        val drops = sql.mapNotNull { DROP.matchEntire(it) }
        drops.size shouldBeGreaterThan 0
        assertSoftly {
            drops.forEach { withClue(it.value) { artistSlugFor(it.groupValues[2]) shouldBe it.groupValues[1] } }
        }
    }

    private fun lineup(sourceId: String): List<String> =
        rows(
            connection,
            "SELECT concat_ws('|', a.name, a.slug, ea.billing_order) FROM events.event_artist ea JOIN events.artist a ON a.id = ea.artist_id " +
                "JOIN events.event e ON e.id = ea.event_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { it.getString(1) }

    private fun slugs(): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun instagram(slug: String): String? = rows(connection, "SELECT instagram_url FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private companion object {
        val ARTISTS =
            listOf(
                Triple("dj-kingston-headroll", "DJ: Kingston Headroll", null),
                Triple("berlitonian", "Berlitonian", null),
                Triple("queen-thunderbolt", "Queen - Thunderbolt", null),
                Triple("lightning", "Lightning", null),
                Triple("two-dots-fr", "Two Dots (FR)", null),
                Triple("jas", "JAS", null),
                Triple("grippe", "Grippe", null),
                Triple("jugo-urgens", "Jugo Ürgens", "https://www.instagram.com/urgens-masked/"),
                Triple("jugo-urdens", "Jugo Ürdens", "https://www.instagram.com/jugo-masked/"),
                Triple("lobo-de", "Lobo (DE)", "https://www.instagram.com/lobo-masked/"),
                Triple("lobo", "Lobo", null),
                Triple("defjam", "Defjam", null),
                Triple("15", "15 €", null),
                Triple("jochen-muller", "Jochen Müller", null),
                Triple("wird", "WIRD", null),
                Triple("flightmode", "Flightmode", null),
                Triple("flight-mode", "Flight Mode", null)
            )
        val LINEUPS =
            mapOf(
                "schokoladen:e20261205" to listOf("dj-kingston-headroll", "berlitonian"),
                "maschinenhaus:-P7BwhOs7rAs5MCXOsTD" to listOf("queen-thunderbolt", "lightning"),
                "lido:night" to listOf("lightning"),
                "manual:oxi:2026-12-19-silikon" to listOf("two-dots-fr"),
                "kesselhaus:jas" to listOf("jas"),
                "manual:sensorium:2026-12-09-techno-mittwoch" to listOf("grippe", "jas"),
                "badehaus:jugo-uergens" to listOf("jugo-urgens"),
                "manual:suss-war-gestern:2026-12-05-the-unknown" to listOf("lobo-de"),
                "soda:x-mas" to listOf("defjam", "15"),
                "urania:talk" to listOf("jochen-muller"),
                "renate:night" to listOf("flight-mode")
            )

        /** A hand-made row no NAME_CORRECTIONS entry folds, because no parser bills it: the survivor's own name. */
        val HAND_MERGES = mapOf("Lobo (DE)" to "Lobo")

        /** `(old_slug, prefix, slug, name)`. */
        val RENAME = Regex("""\s+\('[^']+',\s+'[a-z0-9_]+:',\s+'([^']+)',\s+'([^']+)'\)[,;]""")

        /** `(loser, loser_name, survivor)`; a drop's third value is a prefix and ends in a colon. */
        val MERGE = Regex("""\s+\('[^']+',\s+'([^']+)',\s+'([a-z0-9-]+)'\)[,;]""")

        /** `(slug, name, prefix)`. */
        val DROP = Regex("""\s+\('([^']+)',\s+'([^']+)',\s+'[a-z0-9_]+:'\)[,;]""")
    }
}
