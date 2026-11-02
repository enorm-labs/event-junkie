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

/** Plants production's two rows on DIA DE LOS MUERTOS, runs V215 (#4373), then V215 a second time. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitSisyphosFormatListArtistMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var connection: Connection
    private lateinit var afterFirstRun: List<String>

    @BeforeAll
    fun migrateAndPlant() {
        postgres.start()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway(postgres, "214").migrate()
        connection.createStatement().use { statement ->
            statement.execute("SET search_path TO events")
            statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture', 'fixture')")
            for ((name, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$name', '$slug')")
            plantEvent(statement, DIA_DE_LOS_MUERTOS)
            link(statement, DIA_DE_LOS_MUERTOS, "totenaltar-und-ambient-sound", 31, "'2026-10-30T22:00:00+01:00'", "'2026-10-31T21:00:00+01:00'")
            link(statement, DIA_DE_LOS_MUERTOS, "fanfare-gertrude-brass-band", 32, "'2026-10-31T21:00:00+01:00'", "'2026-10-31T22:00:00+01:00'")
            link(statement, DIA_DE_LOS_MUERTOS, FUSED, 34, "'2026-11-01T20:00:00+01:00'", "'2026-11-01T21:00:00+01:00'")
            link(statement, DIA_DE_LOS_MUERTOS, "steven-shade", 35, "'2026-11-01T21:00:00+01:00'", "'2026-11-02T00:00:00+01:00'")
            // Another venue billing the same slug keeps the row and its link.
            plantEvent(statement, ELSEWHERE)
            link(statement, ELSEWHERE, FUSED, 0, "NULL", "NULL")
        }
        flyway(postgres, "215").migrate()
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
        val acts = ACT_ROW.findAll(sql).map { it.destructured.toList() }.toList()
        acts.map { it[0] } shouldContainExactly listOf(FUSED, FUSED)
        acts.forEach { (_, slug, name) -> artistSlugFor(name) shouldBe slug }
        ARTISTS.forEach { (name, slug) -> artistSlugFor(name) shouldBe slug }
    }

    @Test
    fun `the two acts take the fused row's place in billing order, and the later act moves down one`() {
        billing(DIA_DE_LOS_MUERTOS) shouldContainExactly
            listOf(
                "fanfare-gertrude-brass-band 32 HEADLINER Strand 🎶 2026-10-31 20:00 2026-10-31 21:00",
                "mariachi-leyendas 34 HEADLINER Strand 🎶 2026-11-01 19:00 2026-11-01 20:00",
                "folklore-mexico 35 HEADLINER Strand 🎶 2026-11-01 19:00 2026-11-01 20:00",
                "steven-shade 36 HEADLINER Strand 🎶 2026-11-01 20:00 2026-11-01 23:00"
            )
        name("mariachi-leyendas") shouldBe "Mariachi Leyendas"
        name("folklore-mexico") shouldBe "Folklore Mexico"
    }

    @Test
    fun `drops the installation, and keeps a fused row another venue bills`() {
        billing(ELSEWHERE) shouldContainExactly listOf("$FUSED 0 HEADLINER Strand 🎶 null null")
        slugs() shouldContainExactlyInAnyOrder
            listOf(FUSED, "fanfare-gertrude-brass-band", "steven-shade", "mariachi-leyendas", "folklore-mexico")
    }

    @Test
    fun `a second run changes nothing`() {
        everyLink() shouldBe afterFirstRun
    }

    /** V215's SQL once more, in one transaction: its temporary tables drop on commit. */
    private fun rerun() {
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL search_path TO events")
            statement.execute(Files.readString(MIGRATION))
        }
        connection.commit()
        connection.autoCommit = true
    }

    private fun plantEvent(
        statement: Statement,
        sourceId: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'DIA DE LOS MUERTOS', '${sourceId.replace(':', '-')}', DATE '2026-10-30', '$sourceId' FROM venue"
        )
    }

    private fun link(
        statement: Statement,
        sourceId: String,
        slug: String,
        order: Int,
        setStart: String,
        setEnd: String
    ) {
        statement.execute(
            "INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, set_start, set_end) " +
                "SELECT e.id, a.id, 'HEADLINER', $order, 'Strand 🎶', $setStart, $setEnd FROM event e, artist a " +
                "WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
        )
    }

    private fun billing(sourceId: String): List<String> =
        rows(
            connection,
            "SELECT a.slug, ea.billing_order, ea.role, ea.stage, " +
                "to_char(ea.set_start AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'), to_char(ea.set_end AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') " +
                "FROM events.event_artist ea " +
                "JOIN events.event e ON e.id = ea.event_id JOIN events.artist a ON a.id = ea.artist_id " +
                "WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order"
        ) { "${it.getString(1)} ${it.getInt(2)} ${it.getString(3)} ${it.getString(4)} ${it.getString(5)} ${it.getString(6)}" }

    private fun everyLink(): List<String> = listOf(DIA_DE_LOS_MUERTOS, ELSEWHERE).flatMap { billing(it) } + slugs().sorted()

    private fun slugs(): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private fun name(slug: String): String = rows(connection, "SELECT name FROM events.artist WHERE slug = '$slug'") { it.getString(1) }.single()

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V215__split_sisyphos_format_list_artist.sql")

        /** One `(old_slug, source_prefix, slug, name, position)` row of the replacement list. */
        val ACT_ROW = Regex("""\('([^']+)',\s*'sisyphos:',\s*'([^']+)',\s*'([^']+)',\s*\d+\)""")

        const val DIA_DE_LOS_MUERTOS = "sisyphos:2026-10-30"
        const val ELSEWHERE = "so36:1"
        const val FUSED = "mariachi-leyendas-folklore-mexico-konzert-tanz"

        /** The names production stores, each with the slug the importer gave it. */
        val ARTISTS =
            listOf(
                "Mariachi Leyendas & Folklore Mexico Konzert & Tanz" to FUSED,
                "Totenaltar und Ambient Sound" to "totenaltar-und-ambient-sound",
                "Fanfare Gertrude Brass Band" to "fanfare-gertrude-brass-band",
                "Steven Shade" to "steven-shade"
            )
    }
}
