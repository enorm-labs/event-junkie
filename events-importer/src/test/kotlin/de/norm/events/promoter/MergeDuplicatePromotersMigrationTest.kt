package de.norm.events.promoter

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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

/**
 * Runs the promoter data migrations — V023, V025, V026, V027, V029, V030, V031 — against the rows each names, planted on a
 * database migrated to just before it.
 *
 * The migrations are keyed on slugs read from staging, and a misspelt one updates no row while
 * Flyway records success (#987). Nothing seeds promoters, so `MigrationSlugTest` cannot check
 * them; this test plants the shapes each step handles and reads back what it did.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MergeDuplicatePromotersMigrationTest {
    private val postgres = PostgreSQLContainer("postgres:18.3-alpine")
    private lateinit var connection: Connection

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway("22").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            // A merge onto a survivor that exists, with one event linked to both sides.
            plantPromoter(statement, "Trinity", "trinity")
            plantPromoter(statement, "Trinity Music", "trinity-music")
            plantEvent(statement, "e1", "trinity")
            plantEvent(statement, "e2", "trinity", "trinity-music")
            // A rename only: same slug, corrected display name.
            plantPromoter(statement, "Tv Noir", "tv-noir")
            plantEvent(statement, "e3", "tv-noir")
            // Two losers whose survivor does not exist: one is renamed, the other merged into it.
            plantPromoter(statement, "beav boloney & little league shows", "beav-boloney-little-league-shows")
            plantPromoter(statement, "beav boloney, wild wax & little league shows", "beav-boloney-wild-wax-little-league-shows")
            plantEvent(statement, "e4", "beav-boloney-little-league-shows")
            plantEvent(statement, "e5", "beav-boloney-wild-wax-little-league-shows")
            // A row the migration does not name, left alone.
            plantPromoter(statement, "Listen", "listen")
            plantEvent(statement, "e6", "listen")
        }
        flyway("23").migrate()
        connection.createStatement().use { statement ->
            // V025: the row V023 misnamed, and the one the next import minted beside it.
            plantPromoter(statement, "Greyzone Concerts", "greyzone")
            plantPromoter(statement, "Greyzone Concerts", "greyzone-concerts")
            plantEvent(statement, "g1", "greyzone")
            plantEvent(statement, "g2", "greyzone-concerts")
            // V026: a fragment in the promoter slot, and its event, which must survive it.
            plantPromoter(statement, "Kneipenabend", "kneipenabend")
            plantPromoter(statement, "Schokoladen", "schokoladen")
            plantEvent(statement, "j1", "kneipenabend", "schokoladen")
            // V027: a row the de-shout title-cased, whose new name changes its slug.
            plantPromoter(statement, "Jb Freie", "jb-freie")
            plantEvent(statement, "h1", "jb-freie")
        }
        flyway("27").migrate()
        connection.createStatement().use { statement ->
            // V029: a survivor whose first-listed loser is absent and whose second exists, which V023
            // renamed nothing for; and a row minted before the de-shout.
            plantPromoter(statement, "tip Berlin", "tip-berlin")
            plantEvent(statement, "t1", "tip-berlin")
            plantPromoter(statement, "Stand In Front", "stand-in-front")
            plantEvent(statement, "s1", "stand-in-front")
            // V030: the row Lido's "Atoc Live" credit minted.
            plantPromoter(statement, "Atoc", "atoc")
            plantEvent(statement, "a1", "atoc")
            // V031: the two halves the Urban Spree split minted, one event linked to both, beside
            // the reviewed row (present on staging, absent on production — both shapes).
            plantPromoter(statement, "Pure Obsessions", "pure-obsessions")
            plantPromoter(statement, "Red Nights", "red-nights")
            plantEvent(statement, "u1", "pure-obsessions", "red-nights")
        }
        flyway("31").migrate()
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `moves the loser's events onto the existing survivor and deletes the loser`() {
        promoters().containsKey("trinity") shouldBe false
        promoters()["trinity-music"] shouldBe "Trinity Music"
        eventsOf("trinity-music") shouldContainExactlyInAnyOrder listOf("e1", "e2")
    }

    @Test
    fun `renames a survivor in place`() {
        promoters()["tv-noir"] shouldBe "TV Noir"
        eventsOf("tv-noir") shouldContainExactlyInAnyOrder listOf("e3")
    }

    @Test
    fun `makes a missing survivor out of one loser and merges the other into it`() {
        promoters()["beav-boloney"] shouldBe "beav boloney"
        promoters().keys.none { it.contains("little-league") } shouldBe true
        eventsOf("beav-boloney") shouldContainExactlyInAnyOrder listOf("e4", "e5")
    }

    @Test
    fun `leaves a row it does not name alone`() {
        promoters()["listen"] shouldBe "Listen"
        eventsOf("listen") shouldContainExactlyInAnyOrder listOf("e6")
    }

    @Test
    fun `keeps every event linked to exactly the promoters it had, with no duplicate link`() {
        val links =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT ep.event_id, ep.promoter_id FROM events.event_promoter ep " +
                            "JOIN events.event e ON e.id = ep.event_id WHERE e.source_id LIKE 'e%'"
                    ).use { rows ->
                        generateSequence { if (rows.next()) rows.getLong(1) to rows.getLong(2) else null }.toList()
                    }
            }
        links.distinct().size shouldBe links.size
        links.size shouldBe 6
    }

    // The trap V025 repairs: a survivor whose slug is not its own name's slug is re-minted by the
    // next import, and the admin API can never touch it again.
    @Test
    fun `every survivor in a merge migration carries the slug its name generates`() {
        val pair = Regex("""\('([^']+)',\s*'([^']+)',\s*'([^']+)'\)""")
        val wrong =
            File("src/main/resources/db/migration")
                .listFiles { file -> file.name.contains("merge") && file.extension == "sql" }
                .orEmpty()
                .flatMap { file ->
                    pair
                        .findAll(file.readText())
                        .map { match -> Triple(file.name, match.groupValues[2], match.groupValues[3]) }
                        .filter { (_, survivor, name) -> SlugGenerator.slugify(name) != survivor }
                        .map { (migration, survivor, name) -> "$migration: '$survivor' is named '$name', whose slug is '${SlugGenerator.slugify(name)}'" }
                        .distinct()
                }
        // V023's one wrong pair stays as written, because an applied migration is never edited; V025 repairs it.
        wrong shouldBe listOf("V023__merge_duplicate_promoters.sql: 'greyzone' is named 'Greyzone Concerts', whose slug is 'greyzone-concerts'")
    }

    @Test
    fun `V025 folds the re-minted greyzone row and the old one into the slug the normalizer resolves`() {
        promoters().containsKey("greyzone") shouldBe false
        eventsOf("greyzone-concerts") shouldContainExactlyInAnyOrder listOf("g1", "g2")
    }

    @Test
    fun `V027 renames a title-cased initialism row onto the slug of its trading name`() {
        promoters().containsKey("jb-freie") shouldBe false
        promoters()["jb-freie-musik"] shouldBe "JB Freie Musik"
        eventsOf("jb-freie-musik") shouldContainExactlyInAnyOrder listOf("h1")
    }

    @Test
    fun `V029 makes a missing survivor out of the loser that exists when the first-listed one is absent`() {
        promoters().containsKey("tip-berlin") shouldBe false
        promoters()["tipberlin"] shouldBe "tipBerlin"
        eventsOf("tipberlin") shouldContainExactlyInAnyOrder listOf("t1")
        promoters()["stand-in-front"] shouldBe "Stand in Front"
        eventsOf("stand-in-front") shouldContainExactlyInAnyOrder listOf("s1")
    }

    @Test
    fun `V030 renames the Atoc row onto the slug of its company name`() {
        promoters().containsKey("atoc") shouldBe false
        promoters()["atoc-soundlab"] shouldBe "ATOC Soundlab"
        eventsOf("atoc-soundlab") shouldContainExactlyInAnyOrder listOf("a1")
    }

    @Test
    fun `V031 folds the two halves of a split party name into one row with one link`() {
        promoters().containsKey("pure-obsessions") shouldBe false
        promoters().containsKey("red-nights") shouldBe false
        promoters()["pure-obsessions-red-nights"] shouldBe "Pure Obsessions & Red Nights"
        eventsOf("pure-obsessions-red-nights") shouldContainExactlyInAnyOrder listOf("u1")
    }

    @Test
    fun `V026 deletes a row that names no promoter and leaves its event with its other promoter`() {
        promoters().containsKey("kneipenabend") shouldBe false
        eventsOf("schokoladen") shouldContainExactlyInAnyOrder listOf("j1")
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM events.event WHERE source_id = 'j1'").use { rows ->
                rows.next()
                rows.getInt(1) shouldBe 1
            }
        }
    }

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("events")
            .target(target)
            .load()

    private fun plantPromoter(
        statement: java.sql.Statement,
        name: String,
        slug: String
    ) {
        statement.execute("INSERT INTO promoter (name, slug) VALUES ('${name.replace("'", "''")}', '$slug')")
    }

    private fun plantEvent(
        statement: java.sql.Statement,
        sourceId: String,
        vararg promoterSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, '$sourceId', '$sourceId', DATE '2026-01-01', '$sourceId' FROM venue WHERE slug = 'fixture'"
        )
        for (slug in promoterSlugs) {
            statement.execute(
                "INSERT INTO event_promoter (event_id, promoter_id) " +
                    "SELECT e.id, p.id FROM event e, promoter p WHERE e.source_id = '$sourceId' AND p.slug = '$slug'"
            )
        }
    }

    private fun promoters(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug, name FROM events.promoter").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
            }
        }

    private fun eventsOf(slug: String): List<String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT e.source_id FROM events.event e JOIN events.event_promoter ep ON ep.event_id = e.id " +
                        "JOIN events.promoter p ON p.id = ep.promoter_id WHERE p.slug = '$slug'"
                ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }
}
