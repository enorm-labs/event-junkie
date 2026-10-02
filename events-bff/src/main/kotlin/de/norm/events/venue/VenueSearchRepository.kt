package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.escapeLike
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
    val district: String? = null,
    val types: List<String> = emptyList(),
    val families: List<String> = emptyList(),
    val eventTypes: List<String> = emptyList()
) {
    companion object {
        /** Blank values drop out; lists are trimmed, de-duplicated and sorted, event types upper-cased. */
        fun of(
            query: String?,
            district: String?,
            types: List<String>?,
            families: List<String>?,
            eventTypes: List<String>?
        ): VenueFilter =
            VenueFilter(
                query = query?.trim()?.takeIf { it.isNotEmpty() },
                district = district?.trim()?.takeIf { it.isNotEmpty() },
                types = types.normalized(),
                families = families.normalized(),
                eventTypes = eventTypes.orEmpty().map { it.uppercase() }.normalized()
            )

        private fun List<String>?.normalized(): List<String> =
            orEmpty()
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
 * The venue list query: a name search, a district and the array filters of [VenueFilter], ordered
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
        pageable: Pageable
    ): VenueListPage {
        val params = mutableMapOf<String, Any>()
        filter.query?.let { params["name"] = "%${it.escapeLike()}%" }
        filter.district?.let { params["district"] = it }
        val conditions =
            listOfNotNull(
                "v.name ILIKE :name".takeIf { filter.query != null },
                "v.district = :district".takeIf { filter.district != null }
            ) +
                ARRAY_FILTERS.mapNotNull { (column, param, values) ->
                    values(filter).takeIf { it.isNotEmpty() }?.let {
                        params[param] = it.toTypedArray()
                        "v.$column && :$param"
                    }
                }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        val total =
            databaseClient
                .sql("SELECT COUNT(*) FROM $EVENTS_SCHEMA.venue v $where")
                .bindAll(params)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()

        if (total == 0L) return VenueListPage(emptyList(), 0L)

        val rows =
            databaseClient
                .sql(
                    "SELECT v.id, ($UPCOMING_COUNT) AS upcoming FROM $EVENTS_SCHEMA.venue v $where " +
                        "${orderBy(pageable)} LIMIT :limit OFFSET :offset"
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
            "SELECT COUNT(*) FROM $EVENTS_SCHEMA.event e WHERE e.venue_id = v.id AND e.event_date >= :today"

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
