package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Plants production's marked rows, runs V196's drop, then V197 (#3938) on top of it, and V197 a second time. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RelinkSisyphosSetMarkerArtistsMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection
    private lateinit var afterFirstRun: List<String>

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "195").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for ((name, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
            plant(statement, NICHTGEBURTSTAG, "yetti-meissner" to 0, "christopher-coe-hybrid" to 4, "secret" to 6, "chow-chow-disco-liveact" to 57)
            // The event already bills the clean act beside the marked one.
            plant(statement, KOPFSALAT, "suboptimal-dub" to 0, "suboptimal-dub-konzert" to 43, "pavelo-schnell-konzert" to 46)
            plant(statement, RAVE_IOLI, "micha-stahl" to 0, "matze-sampler-konzert" to 43, "tasheeno-konzert" to 46)
            // Another venue billing the same slug keeps the row and its link.
            plant(statement, ELSEWHERE, "chow-chow-disco-liveact" to 0)
        }
        flyway(postgres, "196").migrate()
        flyway(postgres, "197").migrate()
        afterFirstRun = everyLink()
        rerun()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `every slug in the migration is the one the importer derives from its name`() {
        val sql = Files.readString(MIGRATION)
        val replacements = REPLACEMENT_ROW.findAll(sql).map { it.destructured.toList() }.toList()
        replacements.map { it[0] } shouldContainExactly
            listOf(
                "christopher-coe-hybrid",
                "chow-chow-disco-liveact",
                "suboptimal-dub-konzert",
                "pavelo-schnell-konzert",
                "matze-sampler-konzert",
                "tasheeno-konzert"
            )
        replacements.forEach { (_, slug, name) -> artistSlugFor(name) shouldBe slug }
        val restored = DROPPED_ROW.findAll(sql).map { it.destructured.toList() }.toList()
        restored.map { it[1] } shouldContainExactly listOf("suboptimal-dub", "pavelo-schnell", "matze-sampler", "tasheeno")
        restored.forEach { (_, slug, name) -> artistSlugFor(name) shouldBe slug }
        STORED_NAMES.forEach { (name, slug) -> artistSlugFor(name) shouldBe slug }
        DROP.findAll(sql).map { it.groupValues[1] }.toList() shouldContainExactly listOf("secret")
    }

    @Test
    fun `restores each act V196 dropped at the role, order, stage and set times production billed`() {
        billing(RAVE_IOLI) shouldContainExactly
            listOf(
                "micha-stahl 0 HEADLINER Wintergarten null",
                "matze-sampler 43 HEADLINER Lord Inn 🎶 2026-10-24 21:00",
                "tasheeno 46 HEADLINER Lord Inn 🎶 2026-10-25 21:30"
            )
        name("matze-sampler") shouldBe "Matze Sampler"
    }

    @Test
    fun `an event that already bills the restored act keeps one link to it`() {
        billing(KOPFSALAT) shouldContainExactly
            listOf("suboptimal-dub 0 HEADLINER Wintergarten null", "pavelo-schnell 46 HEADLINER Lord Inn 🎶 2026-10-17 21:00")
        name("pavelo-schnell") shouldBe "Pavelo & Schnell"
    }

    @Test
    fun `an act row that already exists is reused`() {
        name("tasheeno") shouldBe "TASHEENO"
    }

    @Test
    fun `the clean act takes a marked act's place, and the lone marker goes`() {
        billing(NICHTGEBURTSTAG) shouldContainExactly
            listOf(
                "yetti-meissner 0 HEADLINER Wintergarten null",
                "christopher-coe 4 HEADLINER Wintergarten null",
                "chow-chow-disco 57 HEADLINER Wintergarten null"
            )
        name("christopher-coe") shouldBe "Christopher Coe"
    }

    @Test
    fun `drops the marked rows, and keeps a row another venue bills`() {
        billing(ELSEWHERE) shouldContainExactly listOf("chow-chow-disco-liveact 0 HEADLINER Wintergarten null")
        slugs() shouldContainExactlyInAnyOrder
            listOf(
                "yetti-meissner",
                "christopher-coe",
                "chow-chow-disco",
                "chow-chow-disco-liveact",
                "suboptimal-dub",
                "pavelo-schnell",
                "matze-sampler",
                "tasheeno",
                "micha-stahl"
            )
    }

    @Test
    fun `a second run changes nothing`() {
        everyLink() shouldBe afterFirstRun
    }

    /** V197's SQL once more, in one transaction: its temporary tables drop on commit. */
    private fun rerun() {
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL search_path TO events")
            statement.execute(Files.readString(MIGRATION))
        }
        connection.commit()
        connection.autoCommit = true
    }

    private fun plant(
        statement: Statement,
        sourceId: String,
        vararg links: Pair<String, Int>
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-23', '$sourceId' FROM venue"
        )
        for ((slug, order) in links) {
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order, 'Wintergarten' FROM event e, artist a " +
                    "WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun billing(sourceId: String): List<String> =
        rows(
            connection,
            "SELECT a.slug, ea.billing_order, ea.role, ea.stage, to_char(ea.set_start AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') " +
                "FROM events.event_artist ea " +
                "JOIN events.event e ON e.id = ea.event_id JOIN events.artist a ON a.id = ea.artist_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { "${it.getString(1)} ${it.getInt(2)} ${it.getString(3)} ${it.getString(4)} ${it.getString(5)}" }

    private fun everyLink(): List<String> = listOf(NICHTGEBURTSTAG, KOPFSALAT, RAVE_IOLI, ELSEWHERE).flatMap { billing(it) } + slugs().sorted()

    private fun slugs(): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun name(slug: String): String = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V197__relink_sisyphos_set_marker_artists.sql")

        /** One `(old_slug, source_prefix, slug, name)` row of the replacement list. */
        val REPLACEMENT_ROW = Regex("""\('([^']+)',\s*'sisyphos:',\s*'([^']+)',\s*'([^']+)'\)""")

        /** The start of one `(source_id, slug, name, …)` row of the restored links. */
        val DROPPED_ROW = Regex("""\('(sisyphos:[\d-]+)',\s*'([^']+)',\s*'([^']+)',""")
        val DROP = Regex("""drop_artist_billed_only_by\('([^']+)', 'sisyphos:'\)""")

        const val NICHTGEBURTSTAG = "sisyphos:2026-10-02"
        const val KOPFSALAT = "sisyphos:2026-10-16"
        const val RAVE_IOLI = "sisyphos:2026-10-23"
        const val ELSEWHERE = "so36:1"

        /** The names production stores, each with the slug the importer gave it. */
        val STORED_NAMES =
            listOf(
                "Christopher Coe *Hybrid" to "christopher-coe-hybrid",
                "Chow Chow Disco *Liveact" to "chow-chow-disco-liveact",
                "*Secret" to "secret",
                "Suboptimal Dub *Konzert" to "suboptimal-dub-konzert",
                "Pavelo & Schnell *Konzert" to "pavelo-schnell-konzert",
                "Matze Sampler *KONZERT" to "matze-sampler-konzert",
                "Tasheeno *Konzert" to "tasheeno-konzert"
            )
        val ARTISTS =
            STORED_NAMES +
                listOf(
                    "Yetti Meissner" to "yetti-meissner",
                    "Suboptimal Dub" to "suboptimal-dub",
                    "TASHEENO" to "tasheeno",
                    "Micha Stahl" to "micha-stahl"
                )
    }
}
