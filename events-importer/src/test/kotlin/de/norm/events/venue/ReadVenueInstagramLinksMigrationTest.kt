package de.norm.events.venue

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V234 against planted rows on a database migrated to before it. */
class ReadVenueInstagramLinksMigrationTest {
    @Test
    fun `sets a missing link, keeps a hand-set one and leaves an unlisted venue alone`() {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "233").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute(
                        "INSERT INTO venue (name, slug, instagram_url) VALUES " +
                            "('Tresor', 'tresor', NULL), " +
                            "('SO36', 'so36', 'https://www.instagram.com/example/'), " +
                            "('Zitadelle', 'zitadelle', NULL)"
                    )
                }
                flyway(postgres, "234").migrate()

                rows(connection, "SELECT slug, instagram_url FROM events.venue ORDER BY slug") {
                    it.getString(1) to it.getString(2)
                } shouldContainExactly
                    listOf(
                        "so36" to "https://www.instagram.com/example/",
                        "tresor" to "https://www.instagram.com/tresorberlin/",
                        "zitadelle" to null
                    )
            }
        }
    }
}
