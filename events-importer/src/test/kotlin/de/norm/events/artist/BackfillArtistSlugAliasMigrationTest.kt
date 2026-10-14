package de.norm.events.artist

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V160 (#3289) against planted artists on a database migrated to V159. */
class BackfillArtistSlugAliasMigrationTest {
    @Test
    fun `gives each renamed artist its former slug as an alias`() {
        migrated({ statement -> RENAMES.values.forEach { plantArtist(statement, it) } }) { connection ->
            aliases(connection) shouldBe RENAMES
        }
    }

    // Staging never had the renames: every former slug is still an artist there.
    @Test
    fun `writes nothing where the former slug is still an artist`() {
        migrated({ statement -> RENAMES.forEach { (former, current) -> plantArtist(statement, former).also { plantArtist(statement, current) } } }) {
            aliases(it) shouldBe emptyMap()
        }
    }

    @Test
    fun `keeps an alias row that already exists and skips an artist that is missing`() {
        migrated({ statement ->
            plantArtist(statement, "rundfunkchor-berlin")
            plantArtist(statement, "someone-else")
            statement.execute(
                "INSERT INTO artist_slug_alias (slug, artist_id) SELECT 'rundfunkchorlounge', id FROM artist WHERE slug = 'someone-else'"
            )
            plantArtist(statement, "marvelin")
        }) { connection ->
            aliases(connection) shouldBe
                mapOf("rundfunkchorlounge" to "someone-else", "a-night-of-beatbox-with-marvelin" to "marvelin")
        }
    }

    private fun migrated(
        plant: (Statement) -> Unit,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "159").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    plant(statement)
                }
                flyway(postgres, "160").migrate()
                check(connection)
            }
        }
    }

    private fun plantArtist(
        statement: Statement,
        slug: String
    ) {
        statement.execute("INSERT INTO artist (name, slug) VALUES ('$slug', '$slug')")
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

    private fun aliases(connection: Connection): Map<String, String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery("SELECT alias.slug, a.slug FROM events.artist_slug_alias alias JOIN events.artist a ON a.id = alias.artist_id")
                .use { rows -> generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap() }
        }

    private companion object {
        val RENAMES =
            mapOf(
                "a-night-of-beatbox-with-marvelin" to "marvelin",
                "empire-of-giants-jahresabschluss" to "empire-of-giants",
                "orgis-weihnachtsshow" to "king-orgasmus-one",
                "rundfunkchorlounge" to "rundfunkchor-berlin",
                "bad-company-legacy-dave-colwell" to "bad-company-legacy"
            )
    }
}
