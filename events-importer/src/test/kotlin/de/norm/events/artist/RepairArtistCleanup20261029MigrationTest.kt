package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.rows
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V207 against planted rows on a database migrated to before it. */
class RepairArtistCleanup20261029MigrationTest {
    @Test
    fun `the new DJ slug is the one the importer derives from its name`() {
        artistSlugFor("DJ Kassettendeck") shouldBe "dj-kassettendeck"
    }

    @Test
    fun `drops the productions and renames the DJ in place, each on its own source`() {
        migrated(plantSurvivor = false) { connection ->
            billing(connection, BJV) shouldBe listOf("anna-mateur")
            billing(connection, UBER) shouldBe emptyList()
            billing(connection, SOULCAT) shouldBe listOf("dj-kassettendeck")
            billing(connection, OTHER) shouldBe listOf("die-bettwurst")
            rows(connection, "SELECT name FROM events.artist WHERE slug = 'dj-kassettendeck'") { it.getString(1) } shouldBe
                listOf("DJ Kassettendeck")
            slugs(connection) shouldContainExactlyInAnyOrder listOf("anna-mateur", "die-bettwurst", "dj-kassettendeck")
        }
    }

    @Test
    fun `moves the DJ's links onto a row the importer minted first`() {
        migrated(plantSurvivor = true) { connection ->
            billing(connection, SOULCAT) shouldBe listOf("dj-kassettendeck")
            billing(connection, SOULCAT_NEW) shouldBe listOf("dj-kassettendeck")
            slugs(connection) shouldContainExactlyInAnyOrder listOf("anna-mateur", "die-bettwurst", "dj-kassettendeck")
        }
    }

    private fun migrated(
        plantSurvivor: Boolean,
        check: (Connection) -> Unit
    ) = PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
        postgres.start()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            flyway(postgres, "206").migrate()
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO events")
                statement.execute("INSERT INTO venue (name, slug) VALUES ('Fixture venue', 'fixture-venue')")
                for ((artistName, slug) in ARTISTS) statement.execute("INSERT INTO artist (name, slug) VALUES ('$artistName', '$slug')")
                plant(statement, BJV, "die-bettwurst", "anna-mateur")
                plant(statement, UBER, "der-nussknacker")
                plant(statement, SOULCAT, "kassettendeck")
                // A same-slug row another venue bills keeps that link.
                plant(statement, OTHER, "die-bettwurst")
                if (plantSurvivor) {
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('DJ Kassettendeck', 'dj-kassettendeck')")
                    plant(statement, SOULCAT_NEW, "dj-kassettendeck")
                }
            }
            flyway(postgres, "207").migrate()
            check(connection)
        }
    }

    private fun plant(
        statement: Statement,
        sourceId: String,
        vararg artistSlugs: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, title, slug, event_date, source_id) " +
                "SELECT id, 'Fixture night', '${sourceId.replace(':', '-')}', DATE '2026-09-26', '$sourceId' FROM venue"
        )
        artistSlugs.forEachIndexed { order, slug ->
            statement.execute(
                "INSERT INTO event_artist (event_id, artist_id, role, billing_order) " +
                    "SELECT e.id, a.id, 'HEADLINER', $order FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$slug'"
            )
        }
    }

    private fun billing(
        connection: Connection,
        sourceId: String
    ): List<String> =
        rows(
            connection,
            "SELECT a.slug FROM events.event_artist ea JOIN events.event e ON e.id = ea.event_id " +
                "JOIN events.artist a ON a.id = ea.artist_id WHERE e.source_id = '$sourceId' ORDER BY ea.billing_order, a.slug"
        ) { it.getString(1) }

    private fun slugs(connection: Connection): List<String> = rows(connection, "SELECT slug FROM events.artist") { it.getString(1) }

    private companion object {
        const val BJV = "bar_jeder_vernunft:die-bettwurst-2026-09-26"
        const val UBER = "uber_eats_music_hall:der-nussknacker-2026-09-26"
        const val SOULCAT = "soulcat:dj-kassettendeck-soul-rnb-2026-09-26"
        const val SOULCAT_NEW = "soulcat:dj-kassettendeck-soul-rnb-2026-10-29"
        const val OTHER = "bka_theater:die-bettwurst-2026-09-26"
        val ARTISTS =
            listOf(
                "Die Bettwurst" to "die-bettwurst",
                "Anna Mateur" to "anna-mateur",
                "Der Nussknacker" to "der-nussknacker",
                "Kassettendeck" to "kassettendeck"
            )
    }
}
