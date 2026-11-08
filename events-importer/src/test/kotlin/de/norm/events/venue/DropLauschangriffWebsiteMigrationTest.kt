package de.norm.events.venue

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Runs V235 against planted rows on a database migrated to before it. */
class DropLauschangriffWebsiteMigrationTest {
    @Test
    fun `clears the flagged link and keeps the programme link`() {
        migrated(lauschangriffWebsite = "http://www.lauschangriff-berlin.de") { connection ->
            websites(connection) shouldContainExactly
                listOf(
                    Triple("lauschangriff", null, "https://ra.co/clubs/22306"),
                    Triple("tresor", "https://tresorberlin.com", null)
                )
        }
    }

    @Test
    fun `keeps a link someone set by hand`() {
        migrated(lauschangriffWebsite = "https://example.de") { connection ->
            websites(connection).first() shouldBe Triple("lauschangriff", "https://example.de", "https://ra.co/clubs/22306")
        }
    }

    private fun migrated(
        lauschangriffWebsite: String,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "233").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute(
                        "INSERT INTO venue (name, slug, website_url, programme_url) VALUES " +
                            "('Lauschangriff', 'lauschangriff', '$lauschangriffWebsite', 'https://ra.co/clubs/22306'), " +
                            "('Tresor', 'tresor', 'https://tresorberlin.com', NULL)"
                    )
                }
                flyway(postgres, "235").migrate()
                check(connection)
            }
        }
    }

    private fun websites(connection: Connection): List<Triple<String, String?, String?>> =
        rows(connection, "SELECT slug, website_url, programme_url FROM events.venue ORDER BY slug") {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        }
}
