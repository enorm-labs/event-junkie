package de.norm.events.sitemap

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate

/**
 * The slugs each detail sitemap lists. An event is listed until it is over, by its stated end or
 * else its date (ADR-029): a past event's page still answers, but it is not worth a crawl. An
 * artist or promoter is listed while they have such an event, because a page with nothing coming
 * up is thin, and there are thousands of them. Every venue is listed.
 */
@Repository
class SitemapRepository(
    private val databaseClient: DatabaseClient,
    private val clock: Clock
) {
    suspend fun slugs(kind: SitemapKind): List<String> {
        val sql =
            when (kind) {
                SitemapKind.EVENTS -> "SELECT e.slug FROM $EVENTS_SCHEMA.event e WHERE $NOT_OVER ORDER BY e.event_date, e.slug"
                SitemapKind.VENUES -> "SELECT v.slug FROM $EVENTS_SCHEMA.venue v ORDER BY v.slug"
                SitemapKind.ARTISTS -> withUpcomingEvent("artist", "a", "event_artist", "artist_id")
                SitemapKind.PROMOTERS -> withUpcomingEvent("promoter", "p", "event_promoter", "promoter_id")
            }
        val spec = databaseClient.sql(sql)
        return (if (kind == SitemapKind.VENUES) spec else spec.bind("today", LocalDate.now(clock)))
            .map { row -> requireNotNull(row.get(0, String::class.java)) { "A $kind slug came back null" } }
            .all()
            .collectList()
            .awaitSingle()
    }

    private companion object {
        const val NOT_OVER = "COALESCE(e.end_date, e.event_date) >= :today"

        fun withUpcomingEvent(
            table: String,
            alias: String,
            joinTable: String,
            foreignKey: String
        ): String =
            "SELECT $alias.slug FROM $EVENTS_SCHEMA.$table $alias WHERE EXISTS (" +
                "SELECT 1 FROM $EVENTS_SCHEMA.$joinTable j JOIN $EVENTS_SCHEMA.event e ON e.id = j.event_id " +
                "WHERE j.$foreignKey = $alias.id AND $NOT_OVER) ORDER BY $alias.slug"
    }
}
