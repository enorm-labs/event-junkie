package de.norm.events.artist

import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Runs V089 (#2414) against planted rows on a database migrated to just before it: Pöbel & Gesocks
 * split on its night with two support acts after it, `Kai` also billed alone at another venue,
 * `Booze & Glory` already holding a row, and no Tito & Tarantula rows at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MergeSplitSo36AmpersandBandsMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("86").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            listOf(
                "Pöbel" to "pobel",
                "Gesocks" to "gesocks",
                "Grenzer" to "grenzer",
                "Biertoifel" to "biertoifel",
                "Kai" to "kai",
                "Funky von Ton Steine Scherben" to "funky-von-ton-steine-scherben",
                "Booze" to "booze",
                "Glory" to "glory",
                "Booze & Glory" to "booze-glory",
                "Doc Rotten" to "doc-rotten"
            ).forEach { (name, slug) -> plantArtist(statement, name, slug) }
            plantEvent(
                statement,
                "so36-pobel",
                "pobel" to "HEADLINER",
                "gesocks" to "HEADLINER",
                "grenzer" to "SUPPORT",
                "biertoifel" to "SUPPORT"
            )
            plantEvent(statement, "so36-kai", "kai" to "HEADLINER", "funky-von-ton-steine-scherben" to "HEADLINER")
            plantEvent(statement, "elsewhere-kai", "kai" to "DJ")
            plantEvent(statement, "so36-booze", "booze" to "HEADLINER", "glory" to "HEADLINER", "doc-rotten" to "SUPPORT")
        }
        flyway("89").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `bills the whole band in the halves' place and closes the gap`() {
        lineupOf("so36-pobel") shouldContainExactly
            listOf(
                Triple("pobel-gesocks", "HEADLINER", 0),
                Triple("grenzer", "SUPPORT", 1),
                Triple("biertoifel", "SUPPORT", 2)
            )
        artists()["pobel-gesocks"] shouldBe "Pöbel & Gesocks"
        artists() shouldNotContainKey "pobel"
        artists() shouldNotContainKey "gesocks"
    }

    @Test
    fun `keeps a half another night still bills`() {
        lineupOf("so36-kai") shouldContainExactly listOf(Triple("kai-funky-von-ton-steine-scherben", "HEADLINER", 0))
        lineupOf("elsewhere-kai") shouldContainExactly listOf(Triple("kai", "DJ", 0))
        artists() shouldContainKey "kai"
        artists() shouldNotContainKey "funky-von-ton-steine-scherben"
    }

    @Test
    fun `reuses an existing band row`() {
        lineupOf("so36-booze") shouldContainExactly
            listOf(Triple("booze-glory", "HEADLINER", 0), Triple("doc-rotten", "SUPPORT", 1))
        query("SELECT count(*) FROM artist WHERE slug = 'booze-glory'") { it.getInt(1) }.single() shouldBe 1
        artists() shouldNotContainKey "booze"
        artists() shouldNotContainKey "glory"
    }

    @Test
    fun `creates no band a database does not bill`() {
        artists() shouldNotContainKey "tito-tarantula"
    }

    @Test
    fun `every band's name is what the import bills, and its slug is the slug of that name`() {
        val migration = File("src/main/resources/db/migration/V089__merge_split_so36_ampersand_bands.sql").readText()
        val bands = BAND_ROW.findAll(migration).map { it.destructured }.toList()
        bands.size shouldBe 4
        bands.map { it.component2() } shouldContainExactly
            SO36_TITLES.flatMap { title -> headlinersFromTitle(title).map { canonicalArtistName(it.name) } }
        bands
            .filter { (slug, name) -> SlugGenerator.slugify(name) != slug }
            .map { (slug, name) -> "'$slug' is named '$name'" }
            .shouldBeEmpty()
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun plantArtist(
        statement: Statement,
        name: String,
        slug: String
    ) {
        statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
    }

    /** One night billing [lineup] as (artist slug, role), in billing order. */
    private fun plantEvent(
        statement: Statement,
        sourceId: String,
        vararg lineup: Pair<String, String>
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', '$sourceId', DATE '2026-10-03', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        lineup.forEachIndexed { order, (slug, role) ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, '$role', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun artists(): Map<String, String> = query("SELECT slug, name FROM artist") { it.getString(1) to it.getString(2) }.toMap()

    private fun lineupOf(sourceId: String): List<Triple<String, String, Int>> =
        query(
            "SELECT a.slug, ea.role, ea.billing_order FROM event_artist ea " +
                "JOIN artist a ON a.id = ea.artist_id JOIN event e ON e.id = ea.event_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { Triple(it.getString(1), it.getString(2), it.getInt(3)) }

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }

    private companion object {
        /** The titles as SO36 writes them, in the migration's order. */
        val SO36_TITLES = listOf("PÖBEL & GESOCKS", "TITO & TARANTULA", "BOOZE & GLORY", "Kai & Funky von TON STEINE SCHERBEN")

        /** One `('slug', 'Name', 'first', 'second')` row of the band list. */
        val BAND_ROW = Regex("""\('([^']+)',\s*'([^']+)',\s*'[^']+',\s*'[^']+'\)""")
    }
}
