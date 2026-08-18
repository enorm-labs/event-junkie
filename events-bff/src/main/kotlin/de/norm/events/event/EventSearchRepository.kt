package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Optional filter criteria for the public event search. Any combination may be supplied;
 * absent (null/blank) fields impose no constraint.
 */
data class EventFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val eventType: String? = null,
    val venueSlug: String? = null,
    val district: String? = null,
    val artistSlug: String? = null,
    val promoterSlug: String? = null,
    val genreSlug: String? = null,
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val query: String? = null,
    val excludeSoldOut: Boolean = false,
    val onlyFree: Boolean = false
)

/** An ordered page of event IDs plus the total count of matches across all pages. */
data class EventIdPage(
    val ids: List<Long>,
    val total: Long
)

/**
 * Dynamic, parameterized event search.
 *
 * Spring Data R2DBC's derived queries can't express optional multi-criteria filters across
 * join tables, so this builds SQL with [DatabaseClient]: conditions are appended only for the
 * filters that are present, and join-table filters (genre/artist/promoter by slug) use `EXISTS`
 * subqueries to avoid row multiplication. Returns an ordered page of event IDs + a parallel
 * `COUNT(*)`; the service hydrates the rows and batch-loads associations.
 *
 * All values are bound as parameters. Raw SQL must qualify tables with the `events` schema
 * (it bypasses the `NamingStrategy`), consistent with the importer's raw-query convention.
 */
@Repository
class EventSearchRepository(
    private val databaseClient: DatabaseClient
) {
    suspend fun search(
        filter: EventFilter,
        pageable: Pageable
    ): EventIdPage {
        val params = mutableMapOf<String, Any>()
        val where = buildWhereClause(filter, params)

        val total =
            databaseClient
                .sql("SELECT COUNT(*) FROM $EVENTS_SCHEMA.event e $where")
                .bindAll(params)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()

        if (total == 0L) return EventIdPage(emptyList(), 0L)

        val ids =
            databaseClient
                .sql("SELECT e.id FROM $EVENTS_SCHEMA.event e $where ${orderBy(pageable)} LIMIT :limit OFFSET :offset")
                .bindAll(params)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType)!! }
                .all()
                .collectList()
                .awaitSingle()

        return EventIdPage(ids, total)
    }

    /**
     * Every matching event ID in default chronological order, unpaged — backs the calendar view,
     * which renders a whole visible range at once rather than a page of it. Safe to leave unpaged
     * because the caller has already bounded the range (see `EventService.MAX_CALENDAR_DAYS`).
     */
    suspend fun searchAll(filter: EventFilter): List<Long> {
        val params = mutableMapOf<String, Any>()
        val where = buildWhereClause(filter, params)

        return databaseClient
            .sql("SELECT e.id FROM $EVENTS_SCHEMA.event e $where $DEFAULT_ORDER")
            .bindAll(params)
            .map { row: Readable -> row.get(0, Long::class.javaObjectType)!! }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** Assembles the `WHERE` clause for the present filters, registering bound values in [params]. */
    private fun buildWhereClause(
        filter: EventFilter,
        params: MutableMap<String, Any>
    ): String {
        val conditions = mutableListOf<String>()
        appendDateRange(filter, conditions, params)
        appendColumnFilters(filter, conditions, params)
        appendAssociationFilters(filter, conditions, params)
        appendPriceAndQuery(filter, conditions, params)
        return if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
    }

    /** Applies the date-range filter, defaulting to upcoming events when no range is given. */
    private fun appendDateRange(
        filter: EventFilter,
        conditions: MutableList<String>,
        params: MutableMap<String, Any>
    ) {
        if (filter.from == null && filter.to == null) {
            conditions += "e.event_date >= :today"
            params["today"] = LocalDate.now()
            return
        }
        filter.from?.let {
            conditions += "e.event_date >= :from"
            params["from"] = it
        }
        filter.to?.let {
            conditions += "e.event_date <= :to"
            params["to"] = it
        }
    }

    /** Applies filters on columns of the `event` table itself (event type, venue). */
    private fun appendColumnFilters(
        filter: EventFilter,
        conditions: MutableList<String>,
        params: MutableMap<String, Any>
    ) {
        filter.eventType?.takeIf { it.isNotBlank() }?.let {
            conditions += "e.event_type = :eventType"
            params["eventType"] = it.trim().uppercase()
        }
        filter.venueSlug?.takeIf { it.isNotBlank() }?.let {
            conditions += "e.venue_id IN (SELECT id FROM $EVENTS_SCHEMA.venue WHERE slug = :venueSlug)"
            params["venueSlug"] = it.trim()
        }
        filter.district?.takeIf { it.isNotBlank() }?.let {
            conditions += "e.venue_id IN (SELECT id FROM $EVENTS_SCHEMA.venue WHERE district = :district)"
            params["district"] = it.trim()
        }
        if (filter.excludeSoldOut) {
            conditions += "e.sold_out = FALSE"
        }
        if (filter.onlyFree) {
            conditions += "e.free = TRUE"
        }
    }

    /**
     * Applies the many-to-many filters (genre/artist/promoter by slug) as `EXISTS` subqueries.
     * The three share one template, parameterized by [Association]; each subquery correlates on
     * `event_id = e.id` so it tests membership without multiplying the outer rows.
     */
    private fun appendAssociationFilters(
        filter: EventFilter,
        conditions: MutableList<String>,
        params: MutableMap<String, Any>
    ) {
        val slugByAssociation =
            mapOf(
                Association.GENRE to filter.genreSlug,
                Association.ARTIST to filter.artistSlug,
                Association.PROMOTER to filter.promoterSlug
            )
        slugByAssociation.forEach { (association, slug) ->
            slug?.takeIf { it.isNotBlank() }?.let {
                conditions += association.existsClause()
                params[association.param] = it.trim()
            }
        }
    }

    /**
     * Applies the price bounds and the free-text title/subtitle search.
     *
     * Price bounds filter on the effective price: presale when known, otherwise the box-office
     * price (`COALESCE(price_presale, price_box_office)`). A bound still excludes events whose
     * price is entirely unknown (both `NULL`) — such an event shouldn't claim to satisfy a
     * "min €X" filter.
     */
    private fun appendPriceAndQuery(
        filter: EventFilter,
        conditions: MutableList<String>,
        params: MutableMap<String, Any>
    ) {
        filter.minPrice?.let {
            conditions += "COALESCE(e.price_presale, e.price_box_office) >= :minPrice"
            params["minPrice"] = it
        }
        filter.maxPrice?.let {
            conditions += "COALESCE(e.price_presale, e.price_box_office) <= :maxPrice"
            params["maxPrice"] = it
        }
        filter.query?.takeIf { it.isNotBlank() }?.let {
            conditions += "(e.title ILIKE :q OR e.subtitle ILIKE :q)"
            params["q"] = "%${it.trim()}%"
        }
    }

    private fun DatabaseClient.GenericExecuteSpec.bindAll(params: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
        params.entries.fold(this) { spec, (key, value) -> spec.bind(key, value) }

    /**
     * Builds a safe `ORDER BY` clause by whitelisting sort properties to known columns
     * (preventing SQL injection via the `sort` query parameter). Falls back to chronological
     * ordering; a stable tiebreaker on `e.id` keeps pagination deterministic.
     */
    private fun orderBy(pageable: Pageable): String {
        val clauses =
            pageable.sort.toList().flatMap { order ->
                SORT_COLUMNS[order.property]
                    ?.let { column ->
                        listOfNotNull("$column ${if (order.isAscending) "ASC" else "DESC"}", SECONDARY_SORT[column])
                    }.orEmpty()
            }
        return if (clauses.isEmpty()) DEFAULT_ORDER else "ORDER BY ${clauses.joinToString(", ")}, e.id ASC"
    }

    /**
     * The three many-to-many associations filterable by slug. Each describes its join table and
     * the referenced entity table, so [existsClause] can render a correlated `EXISTS` subquery
     * from a single template.
     */
    private enum class Association(
        private val joinTable: String,
        private val joinAlias: String,
        private val foreignKey: String,
        private val refTable: String,
        private val refAlias: String,
        val param: String
    ) {
        GENRE("event_genre_tag", "egt", "genre_tag_id", "genre_tag", "gt", "genreSlug"),
        ARTIST("event_artist", "ea", "artist_id", "artist", "a", "artistSlug"),
        PROMOTER("event_promoter", "ep", "promoter_id", "promoter", "p", "promoterSlug")
        ;

        fun existsClause(): String =
            "EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.$joinTable $joinAlias " +
                "JOIN $EVENTS_SCHEMA.$refTable $refAlias ON $refAlias.id = $joinAlias.$foreignKey " +
                "WHERE $joinAlias.event_id = e.id AND $refAlias.slug = :$param)"
    }

    companion object {
        private val SORT_COLUMNS =
            mapOf(
                "eventDate" to "e.event_date",
                "startTime" to "e.start_time",
                "title" to "e.title",
                "pricePresale" to "e.price_presale"
            )

        /** Stable within-day ordering applied alongside a date sort, so same-day events keep chronological order. */
        private const val START_TIME_TIEBREAKER = "e.start_time ASC NULLS LAST"

        /** Extra ordering appended after a primary sort column, keyed by that column. */
        private val SECONDARY_SORT = mapOf("e.event_date" to START_TIME_TIEBREAKER)
        private const val DEFAULT_ORDER = "ORDER BY e.event_date ASC, $START_TIME_TIEBREAKER, e.id ASC"
    }
}
