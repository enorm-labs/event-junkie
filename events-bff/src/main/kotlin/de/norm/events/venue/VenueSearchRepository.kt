package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.escapeLike
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

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
 * The venue list query: a name search and a district filter, ordered by name or by upcoming
 * events (#360). The same shape as `PromoterSearchRepository`, for the same reasons: the count is a
 * correlated subquery from [today] on, every order ends in `name, id`, and names sort case-folded
 * because the database collation is `C`.
 */
@Repository
class VenueSearchRepository(
    private val databaseClient: DatabaseClient
) {
    suspend fun search(
        query: String?,
        district: String?,
        today: LocalDate,
        pageable: Pageable
    ): VenueListPage {
        val name = query?.trim()?.takeIf { it.isNotEmpty() }
        val districtSlug = district?.trim()?.takeIf { it.isNotEmpty() }
        val conditions =
            listOfNotNull(
                "v.name ILIKE :name".takeIf { name != null },
                "v.district = :district".takeIf { districtSlug != null }
            )
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        val total =
            databaseClient
                .sql("SELECT COUNT(*) FROM $EVENTS_SCHEMA.venue v $where")
                .bindFilters(name, districtSlug)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()

        if (total == 0L) return VenueListPage(emptyList(), 0L)

        val rows =
            databaseClient
                .sql(
                    "SELECT v.id, ($UPCOMING_COUNT) AS upcoming FROM $EVENTS_SCHEMA.venue v $where " +
                        "${orderBy(pageable)} LIMIT :limit OFFSET :offset"
                ).bindFilters(name, districtSlug)
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

    private fun DatabaseClient.GenericExecuteSpec.bindFilters(
        name: String?,
        district: String?
    ): DatabaseClient.GenericExecuteSpec {
        var spec = this
        if (name != null) spec = spec.bind("name", "%${name.escapeLike()}%")
        if (district != null) spec = spec.bind("district", district)
        return spec
    }

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

        private val TIEBREAKER = listOf("lower(v.name) ASC", "v.id ASC")
    }
}
