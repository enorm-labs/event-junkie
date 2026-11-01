package de.norm.events.artist

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.flyway
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V214 against planted artists and promoters on a database migrated to V213. */
class HyphenateUnderscoreSlugsMigrationTest {
    @Test
    fun `moves each underscore slug to the slug the slugger gives now`() {
        migrated({ statement -> PRODUCTION_SLUGS.forEach { plant(statement, "artist", it) } }) { connection ->
            slugs(connection, "artist") shouldBe PRODUCTION_SLUGS.map(SlugGenerator::slugify).toSet()
        }
    }

    @Test
    fun `keeps each former artist slug as an alias`() {
        migrated({ statement -> plant(statement, "artist", "dj_dave") }) { connection ->
            aliases(connection) shouldBe mapOf("dj_dave" to "dj-dave")
        }
    }

    @Test
    fun `leaves a slug alone when its new form is taken`() {
        migrated({ statement ->
            plant(statement, "artist", "early_desire")
            plant(statement, "artist", "early-desire")
            plant(statement, "artist", "a_b")
            plant(statement, "artist", "a__b")
        }) { connection ->
            slugs(connection, "artist") shouldBe setOf("early_desire", "early-desire", "a-b", "a__b")
            aliases(connection) shouldBe mapOf("a_b" to "a-b")
        }
    }

    @Test
    fun `moves a promoter slug too`() {
        migrated({ statement ->
            plant(statement, "promoter", "berlin_live")
            plant(statement, "promoter", "plain")
        }) { connection ->
            slugs(connection, "promoter") shouldBe setOf("berlin-live", "plain")
        }
    }

    private fun migrated(
        plant: (Statement) -> Unit,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer(POSTGRES_TEST_IMAGE).use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "213").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    plant(statement)
                }
                flyway(postgres, "214").migrate()
                check(connection)
            }
        }
    }

    private fun plant(
        statement: Statement,
        table: String,
        slug: String
    ) {
        statement.execute("INSERT INTO $table (name, slug) VALUES ('$slug', '$slug')")
    }

    private fun slugs(
        connection: Connection,
        table: String
    ): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug FROM events.$table").use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toSet() }
        }

    private fun aliases(connection: Connection): Map<String, String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery("SELECT alias.slug, a.slug FROM events.artist_slug_alias alias JOIN events.artist a ON a.id = alias.artist_id")
                .use { rows -> generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap() }
        }

    private companion object {
        // The five artist slugs with `_` on production on 2026-11-01, from the public sitemap.
        val PRODUCTION_SLUGS = listOf("dj_dave", "dj-hauke_pauke", "early_desire", "nd_baumecker", "real_o-_-o_it")
    }
}
