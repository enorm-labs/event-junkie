package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.NextThirtyDays
import de.norm.events.common.TextSearch
import de.norm.events.common.countQuery
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * The venue list's criteria, normalised so that two orders of the same values share a cache entry.
 * Within one list any value matches, except [characters], where a venue needs every tag; across lists every list must match.
 */
data class VenueFilter(
    val query: String? = null,
    val districts: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val families: List<String> = emptyList(),
    val eventTypes: List<String> = emptyList(),
    val characters: List<String> = emptyList()
) {
    companion object {
        /** Blank values drop out; the rest are trimmed, de-duplicated and sorted. */
        fun normalized(values: List<String>?): List<String> =
            values
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
    }
}

/** One venue on a list page: its id, how many of its events are still to come, and how many start in [NextThirtyDays]. */
data class VenueListRow(
    val id: Long,
    val upcomingEventCount: Int,
    val upcomingNext30DaysCount: Int
)

/** An ordered page of [VenueListRow] plus the total count of matches across all pages. */
data class VenueListPage(
    val rows: List<VenueListRow>,
    val total: Long
)

/**
 * The venue list query: a name search ([TextSearch]), the districts, the character tags and the array filters of [VenueFilter], ordered
 * by name or by the events in [NextThirtyDays] (#360, #2694). The same shape as `PromoterSearchRepository`, for the same reasons: the
 * counts are correlated subqueries, every order ends in `name, id`, and names sort case-folded because the database collation is `C`.
 */
@Repository
class VenueSearchRepository(
    private val databaseClient: DatabaseClient
) {
    suspend fun search(
        filter: VenueFilter,
        now: LocalDateTime,
        pageable: Pageable,
        countCap: Int? = null
    ): VenueListPage =
        TextSearch.strictThenSimilar(filter.query, found = { it.total > 0 }) { bySimilarity -> search(filter, bySimilarity, now, pageable, countCap) }

    /**
     * For each character tag, how many venues match [filter] and also carry that tag (#2671). A tag no match carries is absent.
     * One query on the same pass the list takes: the grouping set `()` is the list's total, so a name search that only
     * matches by similarity counts those venues, as the list shows them.
     */
    suspend fun featureCounts(filter: VenueFilter): Map<String, Long> =
        TextSearch
            .strictThenSimilar(filter.query, found = { it.total > 0 }) { bySimilarity -> featureCounts(filter, bySimilarity) }
            .counts

    private suspend fun featureCounts(
        filter: VenueFilter,
        bySimilarity: Boolean
    ): FeatureCounts {
        val (where, params) = where(filter, bySimilarity)
        val rows =
            databaseClient
                .sql(
                    "SELECT t.tag, GROUPING(t.tag) AS whole, count(DISTINCT v.id) AS n FROM $EVENTS_SCHEMA.venue v " +
                        "LEFT JOIN $EVENTS_SCHEMA.venue_character_tag t ON t.venue_id = v.id $where GROUP BY GROUPING SETS ((t.tag), ())"
                ).bindAll(params)
                .map { row: Readable ->
                    Triple(
                        row.get("tag", String::class.java),
                        row.get("whole", Int::class.javaObjectType) == 1,
                        row.get("n", Long::class.javaObjectType) ?: 0L
                    )
                }.all()
                .collectList()
                .awaitSingle()
        return FeatureCounts(
            total = rows.firstOrNull { it.second }?.third ?: 0L,
            // A NULL tag outside the total row is the venues without any tag.
            counts = rows.filter { !it.second && it.first != null }.associate { requireNotNull(it.first) to it.third }
        )
    }

    private data class FeatureCounts(
        val total: Long,
        val counts: Map<String, Long>
    )

    /** The WHERE clause for [filter] on one pass, and its bind values; `v` is the venue. */
    private fun where(
        filter: VenueFilter,
        bySimilarity: Boolean
    ): Pair<String, Map<String, Any>> {
        val params = mutableMapOf<String, Any>()
        filter.query?.let { params += TextSearch.params(it, bySimilarity) }
        val conditions =
            listOfNotNull(
                filter.query?.let { TextSearch.predicate("v.name", it, bySimilarity) },
                "v.district IN (:districts)".takeIf { filter.districts.isNotEmpty() },
                CHARACTER_FILTER.takeIf { filter.characters.isNotEmpty() }
            ) +
                ARRAY_FILTERS.mapNotNull { (column, param, values) ->
                    values(filter).takeIf { it.isNotEmpty() }?.let {
                        params[param] = it.toTypedArray()
                        "v.$column && :$param"
                    }
                }
        if (filter.districts.isNotEmpty()) params["districts"] = filter.districts
        if (filter.characters.isNotEmpty()) {
            params["characters"] = filter.characters
            params["characterCount"] = filter.characters.size.toLong()
        }
        return (if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}") to params
    }

    @Suppress("LongParameterList") // The public search's four, plus the pass.
    private suspend fun search(
        filter: VenueFilter,
        bySimilarity: Boolean,
        now: LocalDateTime,
        pageable: Pageable,
        countCap: Int?
    ): VenueListPage {
        val (where, params) = where(filter, bySimilarity)

        val total =
            databaseClient
                .sql(countQuery("$EVENTS_SCHEMA.venue v", where, countCap))
                .bindAll(params)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()

        if (total == 0L) return VenueListPage(emptyList(), 0L)

        val rows =
            databaseClient
                .sql(
                    "SELECT v.id, ($UPCOMING_COUNT) AS upcoming, ($NEXT_30_DAYS_COUNT) AS next30 FROM $EVENTS_SCHEMA.venue v $where " +
                        "${orderBy(pageable, filter.query, bySimilarity)} LIMIT :limit OFFSET :offset"
                ).bindAll(params + NextThirtyDays.params(now))
                .bind("today", now.toLocalDate())
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .map { row: Readable ->
                    VenueListRow(
                        id = requireNotNull(row.get("id", Long::class.javaObjectType)) { "Venue id projection returned a null id" },
                        upcomingEventCount = row.get("upcoming", Long::class.javaObjectType)?.toInt() ?: 0,
                        upcomingNext30DaysCount = row.get("next30", Long::class.javaObjectType)?.toInt() ?: 0
                    )
                }.all()
                .collectList()
                .awaitSingle()

        return VenueListPage(rows, total)
    }

    private fun DatabaseClient.GenericExecuteSpec.bindAll(params: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
        params.entries.fold(this) { spec, (key, value) -> spec.bind(key, value) }

    /**
     * Whitelists the sort properties to known expressions. Without a sort, a search puts the closest
     * matches first and the list is by name; a chosen sort, A–Z included, is never reordered by relevance.
     */
    private fun orderBy(
        pageable: Pageable,
        term: String?,
        bySimilarity: Boolean
    ): String {
        val clauses =
            pageable.sort.toList().mapNotNull { order ->
                SORT_COLUMNS[order.property]?.let { column -> "$column ${if (order.isAscending) "ASC" else "DESC"}" }
            }
        val rank = TextSearch.rank("v.name", bySimilarity).takeIf { term != null && clauses.isEmpty() }
        return "ORDER BY ${(listOfNotNull(rank) + clauses + TIEBREAKER).joinToString(", ")}"
    }

    companion object {
        private const val UPCOMING_COUNT =
            "SELECT COUNT(*) FROM $EVENTS_SCHEMA.event e WHERE e.venue_id = v.id AND e.event_date >= :today"

        /** The count the list sorts by: one venue's events in [NextThirtyDays]. */
        private val NEXT_30_DAYS_COUNT = "SELECT COUNT(*) FROM $EVENTS_SCHEMA.event e WHERE e.venue_id = v.id AND ${NextThirtyDays.SQL}"

        /**
         * A venue carrying every chosen character tag, because a tag is a requirement and not an alternative (#2670). The key
         * `(venue_id, tag)` makes `count(*)` the number of distinct chosen tags; [VenueFilter.normalized] removes duplicates.
         */
        private const val CHARACTER_FILTER =
            "v.id IN (SELECT ct.venue_id FROM $EVENTS_SCHEMA.venue_character_tag ct WHERE ct.tag IN (:characters) " +
                "GROUP BY ct.venue_id HAVING count(*) = :characterCount)"

        val SORT_COLUMNS =
            mapOf(
                "name" to "lower(v.name)",
                "upcomingEvents" to "next30"
            )

        /** Each array column, its bind name, and the filter list it overlaps with. */
        private val ARRAY_FILTERS: List<Triple<String, String, (VenueFilter) -> List<String>>> =
            listOf(
                Triple("venue_types", "types", VenueFilter::types),
                Triple("programme_families", "families", VenueFilter::families),
                Triple("programme_event_types", "eventTypes", VenueFilter::eventTypes)
            )

        private val TIEBREAKER = listOf("lower(v.name) ASC", "v.id ASC")
    }
}
