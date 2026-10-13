package de.norm.events.promoter

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Runs V153 against planted rows on a database migrated to before it. */
class SplitLarkOrganiserPromotersMigrationTest {
    @Test
    fun `every name is the canonical form the importer stores, under its slug`() {
        val rows = SPLIT_ROW.findAll(Files.readString(MIGRATION)).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.size shouldBe 7
        rows.forEach { (slug, name) ->
            canonicalPromoterName(name) shouldBe name
            SlugGenerator.slugify(name) shouldBe slug
        }
        canonicalPromoterName("Homebound Music") shouldBe "Homebound"
        canonicalPromoterName("BANDU") shouldBe "Bandu"
    }

    @Test
    fun `credits each promoter on the fused row's events and drops the row`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "152").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('LARK', 'lark')")
                    statement.execute(
                        "INSERT INTO promoter (name, slug) VALUES ('Homebound Music, Bandu, Bedroomdisco and FluxFM', " +
                            "'homebound-music-bandu-bedroomdisco-and-fluxfm'), ('FluxFM', 'fluxfm')"
                    )
                    statement.execute(
                        "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                            "SELECT id, 'Fright Years', 'fright-years', DATE '2027-04-02', 'lark:7582' FROM venue"
                    )
                    statement.execute(
                        "INSERT INTO event_promoter (event_id, promoter_id) SELECT e.id, p.id FROM event e, promoter p " +
                            "WHERE p.slug IN ('homebound-music-bandu-bedroomdisco-and-fluxfm', 'fluxfm')"
                    )
                }
                flyway(postgres, "153").migrate()

                credits(connection) shouldContainExactlyInAnyOrder listOf("homebound", "bandu", "bedroomdisco", "fluxfm")
                rows(connection, "SELECT slug FROM events.promoter") { it.getString(1) } shouldContainExactlyInAnyOrder
                    listOf("homebound", "bandu", "bedroomdisco", "fluxfm")
            }
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

    private fun credits(connection: Connection): List<String> =
        rows(connection, "SELECT p.slug FROM events.event_promoter ep JOIN events.promoter p ON p.id = ep.promoter_id") { it.getString(1) }

    private fun <T : Any> rows(
        connection: Connection,
        sql: String,
        read: (java.sql.ResultSet) -> T
    ): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }

    private companion object {
        val MIGRATION: Path = Path.of("src/main/resources/db/migration/V153__split_lark_organiser_promoters.sql")

        /** One `(fused, slug, name)` row of the split list. */
        val SPLIT_ROW = Regex("""\('[^']+',\s*'([^']+)',\s*'([^']+)'\)""")
    }
}
