package de.norm.events.promoter

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.escapeLike
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** One promoter on a list page: its id and how many of its events are still to come. */
data class PromoterListRow(
    val id: Long,
    val upcomingEventCount: Int
)

/** An ordered page of [PromoterListRow] plus the total count of matches across all pages. */
data class PromoterListPage(
    val rows: List<PromoterListRow>,
    val total: Long
)

/**
 * The promoter list query: a name search, ordered by name or by upcoming events (#1349).
 *
 * The count is a correlated subquery on `event_promoter` joined to `event` from [today] on, so a
 * page costs one query however it is sorted, and a derived query could not order by it. Every
 * order ends in `name, id` so a tie between two promoters pages deterministically. Names sort
 * case-folded: the database collation is `C`, which would put "tipBerlin" after "Trinity". Raw
 * SQL qualifies its tables with the `events` schema, as `EventSearchRepository` does.
 */
@Repository
class PromoterSearchRepository(
    private val databaseClient: DatabaseClient
) {
    suspend fun search(
        query: String?,
        today: LocalDate,
        pageable: Pageable
    ): PromoterListPage {
        val name = query?.trim()?.takeIf { it.isNotEmpty() }
        val where = if (name == null) "" else "WHERE p.name ILIKE :name"

        val total =
            databaseClient
                .sql("SELECT COUNT(*) FROM $EVENTS_SCHEMA.promoter p $where")
                .bindName(name)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()

        if (total == 0L) return PromoterListPage(emptyList(), 0L)

        val rows =
            databaseClient
                .sql(
                    "SELECT p.id, ($UPCOMING_COUNT) AS upcoming FROM $EVENTS_SCHEMA.promoter p $where " +
                        "${orderBy(pageable)} LIMIT :limit OFFSET :offset"
                ).bindName(name)
                .bind("today", today)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .map { row: Readable ->
                    PromoterListRow(
                        id = requireNotNull(row.get("id", Long::class.javaObjectType)) { "Promoter id projection returned a null id" },
                        upcomingEventCount = row.get("upcoming", Long::class.javaObjectType)?.toInt() ?: 0
                    )
                }.all()
                .collectList()
                .awaitSingle()

        return PromoterListPage(rows, total)
    }

    private fun DatabaseClient.GenericExecuteSpec.bindName(name: String?): DatabaseClient.GenericExecuteSpec =
        if (name == null) this else bind("name", "%${name.escapeLike()}%")

    /** Whitelists the sort properties to known expressions; anything else falls back to the name. */
    private fun orderBy(pageable: Pageable): String {
        val clauses =
            pageable.sort.toList().mapNotNull { order ->
                SORT_COLUMNS[order.property]?.let { column -> "$column ${if (order.isAscending) "ASC" else "DESC"}" }
            }
        return "ORDER BY ${(clauses + TIEBREAKER).joinToString(", ")}"
    }

    companion object {
        private const val UPCOMING_COUNT =
            "SELECT COUNT(*) FROM $EVENTS_SCHEMA.event_promoter ep " +
                "JOIN $EVENTS_SCHEMA.event e ON e.id = ep.event_id " +
                "WHERE ep.promoter_id = p.id AND e.event_date >= :today"

        val SORT_COLUMNS =
            mapOf(
                "name" to "lower(p.name)",
                "upcomingEvents" to "upcoming"
            )

        private val TIEBREAKER = listOf("lower(p.name) ASC", "p.id ASC")
    }
}
