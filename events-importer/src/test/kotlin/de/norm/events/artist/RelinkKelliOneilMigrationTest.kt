package de.norm.events.artist

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V074 (#2179) against planted Arcanoa rows on a database migrated to before it. */
class RelinkKelliOneilMigrationTest {
    @Test
    fun `moves the September night onto the survivor and deletes the loser`() {
        migrated(withSurvivor = true) { connection ->
            billing(connection) shouldBe
                mapOf(
                    "arcanoa:2026-09-05-kellie-o-neil-the-hidden-grove" to "kelli-o-neil",
                    "arcanoa:2026-11-06-kelli-o-neil-the-hidden-grove" to "kelli-o-neil"
                )
            slugs(connection) shouldBe setOf("kelli-o-neil")
        }
    }

    @Test
    fun `changes nothing where the survivor is missing`() {
        migrated(withSurvivor = false) { connection ->
            billing(connection) shouldBe
                mapOf("arcanoa:2026-09-05-kellie-o-neil-the-hidden-grove" to "kellie-o-neil-the-hidden-grove")
            slugs(connection) shouldBe setOf("kellie-o-neil-the-hidden-grove")
        }
    }

    private fun migrated(
        withSurvivor: Boolean,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "73").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Arcanoa', 'arcanoa')")
                    statement.execute(
                        "INSERT INTO event_source (venue_id, name, slug, url, source_type) " +
                            "SELECT id, 'Arcanoa', 'arcanoa', 'https://www.ssi-media.com/arcanoa/veranst.htm', 'ARCANOA' FROM venue"
                    )
                    plant(statement, "2026-09-05", "Kellie O'Neil - The Hidden Grove", "kellie-o-neil-the-hidden-grove", titleDerived = true)
                    if (withSurvivor) plant(statement, "2026-11-06", "Kelli O'Neil", "kelli-o-neil", titleDerived = false)
                }
                flyway(postgres, "74").migrate()
                check(connection)
            }
        }
    }

    private fun plant(
        statement: java.sql.Statement,
        date: String,
        artist: String,
        slug: String,
        titleDerived: Boolean
    ) {
        val sourceId = "arcanoa:$date-${if (slug == "kelli-o-neil") "kelli-o-neil" else "kellie-o-neil"}-the-hidden-grove"
        statement.execute(
            "INSERT INTO event (venue_id, event_source_id, source_id, title, slug, event_date) " +
                "SELECT v.id, s.id, '$sourceId', 'The Hidden Grove', '$sourceId', DATE '$date' FROM venue v, event_source s"
        )
        statement.execute("INSERT INTO artist (name, slug) VALUES ('${artist.replace("'", "''")}', '$slug')")
        statement.execute(
            "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) " +
                "SELECT e.id, a.id, 'HEADLINER', 0, $titleDerived FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
        )
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

    private fun billing(connection: Connection): Map<String, String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT e.source_id, a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                        "JOIN events.artist a ON a.id = ea.artist_id"
                ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap() }
        }

    private fun slugs(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug FROM events.artist").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
            }
        }
}
