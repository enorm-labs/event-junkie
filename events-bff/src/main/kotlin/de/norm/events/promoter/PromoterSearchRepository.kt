package de.norm.events.promoter

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.NameListOrder
import de.norm.events.common.NextThirtyDays
import de.norm.events.common.TextSearch
import de.norm.events.common.countQuery
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** One promoter on a list page: its id, how many of its events are still to come, and how many start in [NextThirtyDays]. */
data class PromoterListRow(
    val id: Long,
    val upcomingEventCount: Int,
    val upcomingNext30DaysCount: Int
)

/** An ordered page of [PromoterListRow] plus the total count of matches across all pages. */
data class PromoterListPage(
    val rows: List<PromoterListRow>,
    val total: Long
)

/**
 * The promoter list query: a name search ([TextSearch]), ordered by name or by the events in
 * [NextThirtyDays] (#1349, #2694).
 *
 * Both counts are correlated subqueries on `event_promoter` joined to `event`, so a page costs one
 * query however it is sorted, and a derived query could not order by them. Every
 * order ends in `name, id` so a tie between two promoters pages deterministically. Names sort
 * case-folded: the database collation is `C`, which would put "tipBerlin" after "Trinity". Raw
 * SQL qualifies its tables with the `events` schema, as `EventSearchRepository` does.
 */
@Repository
class PromoterSearchRepository(
    private val databaseClient: DatabaseClient
) {
    private val order = NameListOrder("p.name", SORT_COLUMNS, TIEBREAKER)

    suspend fun search(
        query: String?,
        now: LocalDateTime,
        pageable: Pageable,
        countCap: Int? = null
    ): PromoterListPage {
        val term = TextSearch.term(query)
        return TextSearch.strictThenSimilar(term, found = { it.total > 0 }) { bySimilarity -> search(term, bySimilarity, now, pageable, countCap) }
    }

    @Suppress("LongParameterList") // The public search's four, plus the pass.
    private suspend fun search(
        term: String?,
        bySimilarity: Boolean,
        now: LocalDateTime,
        pageable: Pageable,
        countCap: Int?
    ): PromoterListPage {
        val where = if (term == null) "" else "WHERE ${TextSearch.predicate("p.name", term, bySimilarity)}"

        val total =
            databaseClient
                .sql(countQuery("$EVENTS_SCHEMA.promoter p", where, countCap))
                .bindTerm(term, bySimilarity)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()

        if (total == 0L) return PromoterListPage(emptyList(), 0L)

        val rows =
            databaseClient
                .sql(
                    "SELECT p.id, ($UPCOMING_COUNT) AS upcoming, ($NEXT_30_DAYS_COUNT) AS next30 FROM $EVENTS_SCHEMA.promoter p $where " +
                        "${order.clause(pageable, term, bySimilarity)} LIMIT :limit OFFSET :offset"
                ).bindTerm(term, bySimilarity)
                .bindAll(NextThirtyDays.params(now))
                .bind("today", now.toLocalDate())
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .map { row: Readable ->
                    PromoterListRow(
                        id = requireNotNull(row.get("id", Long::class.javaObjectType)) { "Promoter id projection returned a null id" },
                        upcomingEventCount = row.get("upcoming", Long::class.javaObjectType)?.toInt() ?: 0,
                        upcomingNext30DaysCount = row.get("next30", Long::class.javaObjectType)?.toInt() ?: 0
                    )
                }.all()
                .collectList()
                .awaitSingle()

        return PromoterListPage(rows, total)
    }

    private fun DatabaseClient.GenericExecuteSpec.bindTerm(
        term: String?,
        bySimilarity: Boolean
    ): DatabaseClient.GenericExecuteSpec = if (term == null) this else bindAll(TextSearch.params(term, bySimilarity))

    private fun DatabaseClient.GenericExecuteSpec.bindAll(params: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
        params.entries.fold(this) { spec, (key, value) -> spec.bind(key, value) }

    companion object {
        private const val UPCOMING_COUNT =
            "SELECT COUNT(*) FROM $EVENTS_SCHEMA.event_promoter ep " +
                "JOIN $EVENTS_SCHEMA.event e ON e.id = ep.event_id " +
                "WHERE ep.promoter_id = p.id AND e.event_date >= :today"

        /** The count the list sorts by: one promoter's events in [NextThirtyDays]. */
        private val NEXT_30_DAYS_COUNT =
            "SELECT COUNT(*) FROM $EVENTS_SCHEMA.event_promoter ep " +
                "JOIN $EVENTS_SCHEMA.event e ON e.id = ep.event_id " +
                "WHERE ep.promoter_id = p.id AND ${NextThirtyDays.SQL}"

        val SORT_COLUMNS =
            mapOf(
                "name" to "lower(p.name)",
                "upcomingEvents" to "next30"
            )

        private val TIEBREAKER = listOf("lower(p.name) ASC", "p.id ASC")
    }
}
