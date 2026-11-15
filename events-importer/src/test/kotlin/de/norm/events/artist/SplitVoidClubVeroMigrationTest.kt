package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V255 against planted rows on a database migrated to before it. */
class SplitVoidClubVeroMigrationTest {
    private fun afterV255(plantedArtists: String): Pair<List<String>, List<String>> =
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "252").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO artist (name, slug) VALUES $plantedArtists")
                }
                flyway(postgres, "255").migrate()
                val artists = rows(connection, "SELECT slug || '=' || name FROM events.artist ORDER BY slug") { it.getString(1) }
                val aliases =
                    rows(
                        connection,
                        "SELECT s.slug || '->' || a.slug FROM events.artist_slug_alias s JOIN events.artist a ON a.id = s.artist_id ORDER BY s.slug"
                    ) { it.getString(1) }
                artists to aliases
            }
        }

    @Test
    fun `moves VOID Club's Vero_ to vero-2, keeps its old slug as an alias and leaves the rapper alone`() {
        afterV255("('Vero_', 'vero_'), ('Vero', 'vero')") shouldBe
            (listOf("vero=Vero", "vero-2=Vero_") to listOf("vero_->vero-2"))
    }

    @Test
    fun `leaves a row renamed by hand alone`() {
        afterV255("('Vero Underscore', 'vero_')") shouldBe (listOf("vero_=Vero Underscore") to emptyList())
    }
}
