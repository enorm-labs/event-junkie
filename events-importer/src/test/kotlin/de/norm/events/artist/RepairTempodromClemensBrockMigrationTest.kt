package de.norm.events.artist

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V155 (#3162) against planted rows on a database migrated to before it. */
class RepairTempodromClemensBrockMigrationTest {
    @Test
    fun `the act's slug is the one the importer derives from its name`() {
        artistSlugFor("Clemens Brock") shouldBe "clemens-brock"
    }

    @Test
    fun `moves the past night's link onto Clemens Brock and leaves another venue's row alone`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "154").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Clemens Brock - Der Vadda', 'clemens-brock-der-vadda')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Other Act', 'other-act')")
                    plant(statement, BROCK_NIGHT, "clemens-brock-der-vadda", "other-act")
                    plant(statement, OTHER_TEMPODROM_NIGHT, "clemens-brock-der-vadda")
                }
                flyway(postgres, "155").migrate()

                billing(connection, BROCK_NIGHT) shouldBe listOf("clemens-brock" to "HEADLINER", "other-act" to "SUPPORT")
                billing(connection, OTHER_TEMPODROM_NIGHT) shouldBe emptyList()
                slugs(connection) shouldContainExactlyInAnyOrder listOf("clemens-brock", "other-act")
            }
        }
    }

    @Test
    fun `creates nothing on a database without the night`() {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "154").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Clemens Brock - Der Vadda', 'clemens-brock-der-vadda')")
                    plant(statement, ELSEWHERE, "clemens-brock-der-vadda")
                }
                flyway(postgres, "155").migrate()

                billing(connection, ELSEWHERE) shouldBe listOf("clemens-brock-der-vadda" to "HEADLINER")
                slugs(connection) shouldContainExactlyInAnyOrder listOf("clemens-brock-der-vadda")
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
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-10-05', '$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            val role = if (order == 0) "HEADLINER" else "SUPPORT"
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) " +
                    "SELECT e.id, a.id, '$role', $order, ${order == 0} FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
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
    ): List<Pair<String, String>> =
        rows(
            connection,
            "SELECT a.slug, ea.role FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) to it.getString(2) }

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
        const val BROCK_NIGHT = "tempodrom:clemens_brock-der_vadda_2026-10-05_20"
        const val OTHER_TEMPODROM_NIGHT = "tempodrom:clemens_brock-der_vadda_2026-10-06_20"
        const val ELSEWHERE = "so36:1"
    }
}
