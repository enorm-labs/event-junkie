package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/** Runs V271 against planted rows on a database migrated to before it. */
class SplitMaschinenhausMoeMigrationTest {
    @Test
    fun `moves Maschinenhaus's links to a moé row and leaves the DJ's billings on moe`() {
        afterV271(
            artistName = "moe.",
            sourceIds = listOf("maschinenhaus:-P2ISaUwsVAC_n2hoo6S", "maschinenhaus:-P4skis7gSwqEw2uywwZ", "migas:moe", "gretchen:3539")
        ) shouldContainExactly
            listOf(
                "gretchen:3539=moe=moe.",
                "maschinenhaus:-P2ISaUwsVAC_n2hoo6S=moe-2=moé",
                "maschinenhaus:-P4skis7gSwqEw2uywwZ=moe-2=moé",
                "migas:moe=moe=moe."
            )
    }

    @Test
    fun `creates no row when Maschinenhaus bills nothing on moe`() {
        afterV271(artistName = "moe.", sourceIds = listOf("migas:moe")) shouldContainExactly listOf("migas:moe=moe=moe.")
    }

    @Test
    fun `leaves a row renamed by hand alone`() {
        afterV271(artistName = "Moe Renamed", sourceIds = listOf("maschinenhaus:x")) shouldContainExactly
            listOf("maschinenhaus:x=moe=Moe Renamed")
    }

    /** Each link after V271 as `source_id=slug=name`, plus every artist row no event bills as `-=slug=name`. */
    private fun afterV271(
        artistName: String,
        sourceIds: List<String>
    ): List<String> =
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "270").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', 'moe')")
                    for (sourceId in sourceIds) {
                        statement.execute(
                            "INSERT INTO event (venue_id, title, slug, event_date, source_id) SELECT id, 'Fixture night', " +
                                "'${SlugGenerator.slugify(sourceId)}', DATE '2026-12-03', '$sourceId' FROM venue"
                        )
                        statement.execute(
                            "INSERT INTO event_artist (event_id, artist_id, role, billing_order) SELECT e.id, a.id, 'HEADLINER', 0 " +
                                "FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = 'moe'"
                        )
                    }
                }
                flyway(postgres, "271").migrate()
                rows(
                    connection,
                    "SELECT coalesce(e.source_id, '-') || '=' || a.slug || '=' || a.name FROM events.artist a " +
                        "LEFT JOIN events.event_artist ea ON ea.artist_id = a.id LEFT JOIN events.event e ON e.id = ea.event_id " +
                        "ORDER BY 1"
                ) { it.getString(1) }
            }
        }
}
