package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.TextSearch
import de.norm.events.common.countQuery
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * The venue list's criteria, normalised so that two orders of the same values share a cache entry.
 * Within one list any value matches; across lists every list must match.
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

/** One venue on a list page: its id and how many of its events are still to come. */
data class VenueListRow(
    val id: Long,
    val upcomingEventCount: Int
)

/** An ordered page of [VenueListRow] plus the total count of matches across all pages. */
data class VenueListPage(
    val rows: List<VenueListRow>,
    val total: Long
)

/**
 * The venue list query: a name search ([TextSearch]), the districts, the character tags and the array filters of [VenueFilter], ordered
 * by name or by upcoming events (#360). The same shape as `PromoterSearchRepository`, for the same reasons: the count is a
 * correlated subquery from [today] on, every order ends in `name, id`, and names sort case-folded
 * because the database collation is `C`.
 */
@Repository
class VenueSearchRepository(
    private val databaseClient: DatabaseClient
) {
    suspend fun search(
        filter: VenueFilter,
        today: LocalDate,
        pageable: Pageable,
        countCap: Int? = null
    ): VenueListPage =
        TextSearch.strictThenSimilar(filter.query, found = { it.total > 0 }) { bySimilarity -> search(filter, bySimilarity, today, pageable, countCap) }

    @Suppress("LongParameterList") // The public search's four, plus the pass.
    private suspend fun search(
        filter: VenueFilter,
        bySimilarity: Boolean,
        today: LocalDate,
        pageable: Pageable,
        countCap: Int?
    ): VenueListPage {
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
        if (filter.characters.isNotEmpty()) params["characters"] = filter.characters
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

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
                    "SELECT v.id, ($UPCOMING_COUNT) AS upcoming FROM $EVENTS_SCHEMA.venue v $where " +
                        "${orderBy(pageable, filter.query, bySimilarity)} LIMIT :limit OFFSET :offset"
                ).bindAll(params)
                .bind("today", today)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .map { row: Readable ->
                    VenueListRow(
                        id = requireNotNull(row.get("id", Long::class.javaObjectType)) { "Venue id projection returned a null id" },
                        upcomingEventCount = row.get("upcoming", Long::class.javaObjectType)?.toInt() ?: 0
                    )
                }.all()
                .collectList()
                .awaitSingle()

        return VenueListPage(rows, total)
    }

    private fun DatabaseClient.GenericExecuteSpec.bindAll(params: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
        params.entries.fold(this) { spec, (key, value) -> spec.bind(key, value) }

    /**
     * Whitelists the sort properties to known expressions; anything else falls back to the name. A
     * search sorted by name puts the closest matches first.
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
        val rank = TextSearch.rank("v.name", bySimilarity).takeIf { term != null && pageable.sort.firstOrNull()?.property == "name" }
        return "ORDER BY ${(listOfNotNull(rank) + clauses + TIEBREAKER).joinToString(", ")}"
    }

    companion object {
        private const val UPCOMING_COUNT =
            "SELECT COUNT(*) FROM $EVENTS_SCHEMA.event e WHERE e.venue_id = v.id AND e.event_date >= :today"

        /** A venue carrying any of the chosen character tags (#2379), from the side table that keeps each tag's source. */
        private const val CHARACTER_FILTER =
            "EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.venue_character_tag ct WHERE ct.venue_id = v.id AND ct.tag IN (:characters))"

        val SORT_COLUMNS =
            mapOf(
                "name" to "lower(v.name)",
                "upcomingEvents" to "upcoming"
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
