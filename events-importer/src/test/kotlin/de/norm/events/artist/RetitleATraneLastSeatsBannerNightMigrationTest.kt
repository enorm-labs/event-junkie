package de.norm.events.artist

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** Runs V148 (#3165) against planted A-Trane rows on a database migrated to before it. */
class RetitleATraneLastSeatsBannerNightMigrationTest {
    @Test
    fun `retitles the banner night, drops the banner artist and bills the act`() {
        migrated(bannerBilledElsewhere = false) { connection ->
            rows(connection, "SELECT title, subtitle FROM events.event WHERE event_date = DATE '2026-10-10'") shouldBe
                listOf(listOf("DAVID HAYNES AND FRIENDS", "«SOULFUL FALL» · FEAT: Jesse Milliner-CHARIS KARANTZAS-BEN JUD"))
            billing(connection) shouldBe
                setOf(
                    listOf("2026-10-09", "david-haynes-and-friends", "HEADLINER", "0", "true"),
                    listOf("2026-10-10", "david-haynes-and-friends", "HEADLINER", "0", "true")
                )
            rows(connection, "SELECT slug FROM events.artist").flatten() shouldBe listOf(ACT_SLUG)
        }
    }

    @Test
    fun `leaves the night before and an artist another venue bills alone`() {
        migrated(bannerBilledElsewhere = true) { connection ->
            rows(connection, "SELECT title FROM events.event WHERE event_date = DATE '2026-10-09'").flatten() shouldBe
                listOf("DAVID HAYNES AND FRIENDS")
            rows(connection, "SELECT slug FROM events.artist").flatten().toSet() shouldBe setOf(ACT_SLUG, BANNER_SLUG)
            billing(connection).filter { it[0] == "2026-10-10" }.map { it[1] } shouldBe listOf(ACT_SLUG)
        }
    }

    private fun migrated(
        bannerBilledElsewhere: Boolean,
        check: (Connection) -> Unit
    ) {
        PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                flyway(postgres, "142").migrate()
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO events")
                    statement.execute("INSERT INTO venue (name, slug) VALUES ('A-Trane', 'a-trane'), ('Elsewhere', 'elsewhere')")
                    statement.execute(
                        "INSERT INTO event_source (venue_id, name, slug, url, source_type) " +
                            "SELECT id, name, slug, 'https://' || slug || '.example/', upper(replace(slug, '-', '_')) FROM venue"
                    )
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('David Haynes And Friends', 'david-haynes-and-friends')")
                    statement.execute("INSERT INTO artist (name, slug) VALUES ('Letzten Plätze An Der Bar-Last Seats Left At The Bar', '$BANNER_SLUG')")
                    plant(statement, "a-trane", "${PREFIX}1$NIGHT", "2026-10-09", "DAVID HAYNES AND FRIENDS", "«SOULFUL FALL»", ACT_SLUG)
                    plant(statement, "a-trane", "${PREFIX}2$NIGHT", "2026-10-10", BANNER, "DAVID HAYNES AND FRIENDS · $SUBTITLE", BANNER_SLUG)
                    if (bannerBilledElsewhere) plant(statement, "elsewhere", "elsewhere:last-seats", "2026-10-11", "Last Seats", null, BANNER_SLUG)
                }
                flyway(postgres, "148").migrate()
                check(connection)
            }
        }
    }

    private fun plant(
        statement: Statement,
        venue: String,
        sourceId: String,
        date: String,
        title: String,
        subtitle: String?,
        artistSlug: String
    ) {
        statement.execute(
            "INSERT INTO event (venue_id, event_source_id, source_id, title, subtitle, slug, event_date) " +
                "SELECT v.id, s.id, '$sourceId', '$title', ${subtitle?.let { "'$it'" } ?: "NULL"}, '$date-$venue', DATE '$date' " +
                "FROM venue v JOIN event_source s ON s.venue_id = v.id WHERE v.slug = '$venue'"
        )
        statement.execute(
            "INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived) " +
                "SELECT e.id, a.id, 'HEADLINER', 0, true FROM event e, artist a WHERE e.source_id = '$sourceId' AND a.slug = '$artistSlug'"
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

    private fun billing(connection: Connection): Set<List<String>> =
        rows(
            connection,
            "SELECT e.event_date::text, a.slug, ea.role, ea.billing_order::text, ea.title_derived::text FROM events.event_artist ea " +
                "JOIN events.event e ON e.id = ea.event_id JOIN events.artist a ON a.id = ea.artist_id WHERE starts_with(e.source_id, 'a_trane:')"
        ).toSet()

    private fun rows(
        connection: Connection,
        sql: String
    ): List<List<String>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val columns = rows.metaData.columnCount
                generateSequence { if (rows.next()) (1..columns).map { rows.getString(it) } else null }.toList()
            }
        }

    private companion object {
        const val PREFIX = "a_trane:a-trane-presents-day"
        const val NIGHT =
            "david-haynes-and-friendssoulful-fallwith-special-guests-ania-szarmach-worthy-davismeets-will-jacobsfeat-jesse-milliner-charis-karantzas-ben-jud"
        const val BANNER = "LETZTEN PLÄTZE AN DER BAR-LAST SEATS LEFT AT THE BAR"
        const val BANNER_SLUG = "letzten-platze-an-der-bar-last-seats-left-at-the-bar"
        const val ACT_SLUG = "david-haynes-and-friends"
        const val SUBTITLE = "«SOULFUL FALL» · FEAT: Jesse Milliner-CHARIS KARANTZAS-BEN JUD"
    }
}
