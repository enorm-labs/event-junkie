package de.norm.events.promoter

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V238 against planted rows on a database migrated to before it. */
class MergePunkfimfestAndPsychbergPromotersMigrationTest {
    @Test
    fun `leaves the two survivors, each linked to its own event and the loser's`() {
        migrated(PUNKFILMFEST, PUNKFIMFEST, PSYCHBERG_AFFAIRS, PSYCHBERG) { connection ->
            promoters(connection).map { it.second } shouldContainExactly listOf("psychberg-affairs", "punkfilmfest-berlin")
            credits(connection, "s:1") shouldContainExactly listOf("punkfilmfest-berlin")
            credits(connection, "s:2") shouldContainExactly listOf("punkfilmfest-berlin")
            credits(connection, "s:3") shouldContainExactly listOf("psychberg-affairs")
            credits(connection, "s:4") shouldContainExactly listOf("psychberg-affairs")
        }
    }

    @Test
    fun `renames a loser in place when no survivor exists`() {
        migrated(PUNKFIMFEST, PSYCHBERG) { connection ->
            promoters(connection).map { it.second to it.third } shouldContainExactly
                listOf("psychberg-affairs" to "psychberg affairs", "punkfilmfest-berlin" to "punkfilmfest berlin")
            promoters(connection).forEach { (_, slug, name) -> SlugGenerator.slugify(name) shouldBe slug }
            credits(connection, "s:2") shouldContainExactly listOf("punkfilmfest-berlin")
            credits(connection, "s:4") shouldContainExactly listOf("psychberg-affairs")
        }
    }

    @Test
    fun `is a no-op when the losers are absent`() {
        migrated(PUNKFILMFEST, PSYCHBERG_AFFAIRS) { connection ->
            promoters(connection).map { it.second } shouldContainExactly listOf("psychberg-affairs", "punkfilmfest-berlin")
            credits(connection, "s:1") shouldContainExactly listOf("punkfilmfest-berlin")
            credits(connection, "s:2").shouldBeEmpty()
        }
    }

    @Test
    fun `leaves an unrelated promoter alone`() {
        migrated(PUNKFILMFEST, "Little League Shows" to "little-league-shows") { connection ->
            promoters(connection).map { it.second } shouldContainExactly listOf("little-league-shows", "punkfilmfest-berlin")
        }
    }

    /**
     * Plants [promoters] and four Schokoladen events, credited `s:1` to `punkfilmfest-berlin`, `s:2` to `punkfimfest`, `s:3` to
     * `psychberg-affairs` and `s:4` to `psychberg` where that row exists. Then migrates V238 and hands [check] the connection.
     */
    private fun migrated(
        vararg promoters: Pair<String, String>,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "237").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Schokoladen', 'schokoladen')")
                    for ((name, slug) in promoters) statement.execute("INSERT INTO promoter (name, slug) VALUES ('$name', '$slug')")
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT v.id, 'Night ' || n, '2026-12-0' || n || '-schokoladen-night-' || n, " +
                            "DATE '2026-12-01' + n, 's:' || n FROM venue v, generate_series(1, 4) AS n"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e " +
                            "JOIN (VALUES ('s:1', 'punkfilmfest-berlin'), ('s:2', 'punkfimfest'), " +
                            "('s:3', 'psychberg-affairs'), ('s:4', 'psychberg')) AS c(src, slug) ON c.src = e.source_id " +
                            "JOIN promoter p ON p.slug = c.slug"
                    )
                }
                flyway(postgres, "238").migrate()
                check(connection)
            }
        }
    }

    private fun promoters(connection: Connection): List<Triple<Long, String, String>> =
        rows(connection, "SELECT id, slug, name FROM events.promoter ORDER BY slug") {
            Triple(it.getLong(1), it.getString(2), it.getString(3))
        }

    private fun credits(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT p.slug FROM events.event_promoter ep JOIN events.event e ON e.id = ep.event_id " +
                "JOIN events.promoter p ON p.id = ep.promoter_id WHERE e.source_id = '$sourceId' ORDER BY p.slug"
        ) { it.getString(1) }

    private companion object {
        val PUNKFILMFEST = "punkfilmfest berlin" to "punkfilmfest-berlin"
        val PUNKFIMFEST = "punkfimfest" to "punkfimfest"
        val PSYCHBERG_AFFAIRS = "psychberg affairs" to "psychberg-affairs"
        val PSYCHBERG = "psychberg" to "psychberg"
    }
}
