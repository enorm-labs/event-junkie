package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.escapeLike
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Optional filter criteria for the public event search. Any combination may be supplied;
 * absent (null/blank) fields impose no constraint.
 */
data class EventFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /**
     * The first day of a window: rows whose effective end (ADR-029) is on or after it, so a run that
     * opened earlier is still in. The calendar sets it with [to] (#1405). Wins over [from].
     */
    val runningFrom: LocalDate? = null,
    /**
     * A day the event is on: started by it and not ended before it (ADR-029). Internal, set by the
     * Tonight feed. Wins over [from] and [to].
     */
    val on: LocalDate? = null,
    val eventType: String? = null,
    val venueSlug: String? = null,
    val district: String? = null,
    val artistSlug: String? = null,
    val promoterSlug: String? = null,
    val genreSlug: String? = null,
    val familySlug: String? = null,
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
 * Dynamic, parameterized event search. Derived queries cannot express optional multi-criteria
 * filters across join tables, so this builds SQL with [DatabaseClient]: conditions only for the
 * present filters, and join-table filters as `EXISTS` subqueries to avoid row multiplication.
 * Returns an ordered page of event IDs plus a `COUNT(*)`; the service hydrates the rows.
 *
 * All values are bound. Raw SQL must qualify tables with the `events` schema, since it bypasses
 * the `NamingStrategy`.
 */
@Repository
class EventSearchRepository(
    private val databaseClient: DatabaseClient,
    private val clock: Clock
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
                .bind("seed", tiebreakSeed())
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .map { row: Readable -> row.requiredEventId() }
                .all()
                .collectList()
                .awaitSingle()

        return EventIdPage(ids, total)
    }

    /**
     * Every matching event ID in default chronological order, unpaged, for the calendar view. Safe
     * because the caller bounds the range (`EventService.MAX_CALENDAR_DAYS`).
     */
    suspend fun searchAll(filter: EventFilter): List<Long> {
        val params = mutableMapOf<String, Any>()
        val where = buildWhereClause(filter, params)

        return databaseClient
            .sql("SELECT e.id FROM $EVENTS_SCHEMA.event e $where $DEFAULT_ORDER")
            .bindAll(params)
            .bind("seed", tiebreakSeed())
            .map { row: Readable -> row.requiredEventId() }
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

    /**
     * Applies the date filter. With no range, every event that has not ended (ADR-029). An explicit
     * [EventFilter.from] means "starts on or after", which keeps a running event out of Upcoming
     * (`from = tomorrow`) while Tonight carries it. [EventFilter.on] is the Tonight case. "Not over"
     * carries the late-night grace (#299), see [lateNightGrace].
     */
    private fun appendDateRange(
        filter: EventFilter,
        conditions: MutableList<String>,
        params: MutableMap<String, Any>
    ) {
        filter.on?.let {
            conditions += "e.event_date <= :on AND (${notOverOn(":on", it, params)})"
            params["on"] = it
            return
        }
        if (filter.from == null && filter.runningFrom == null && filter.to == null) {
            val today = LocalDate.now(clock)
            conditions += notOverOn(":today", today, params)
            params["today"] = today
            return
        }
        filter.runningFrom?.let {
            conditions += "$EFFECTIVE_END >= :runningFrom"
            params["runningFrom"] = it
        } ?: filter.from?.let {
            conditions += "e.event_date >= :from"
            params["from"] = it
        }
        filter.to?.let {
            conditions += "e.event_date <= :to"
            params["to"] = it
        }
    }

    /**
     * The rows that are not over on [day], bound as [dayParam]: their effective end is after it, or
     * on it and not yet passed, or the late-night grace covers them. On the clock's own day a stated
     * `end_time` (ADR-029) is the venue's word: a night that ends at 04:00 is over at 04:00. Any
     * other day, or no end time, keeps the row through its whole end date.
     *
     * The grace is the club night's shape (#299): before 06:00 on the clock, and only when [day] is
     * the clock's own day, yesterday's events with no `end_date` and an effective start of 22:00 or
     * later are still on. The effective start includes the #1384 slot. A stated end gets no grace.
     */
    private fun notOverOn(
        dayParam: String,
        day: LocalDate,
        params: MutableMap<String, Any>
    ): String {
        val now = LocalDateTime.now(clock)
        if (day != now.toLocalDate()) return "$EFFECTIVE_END >= $dayParam"
        params["now"] = now.toLocalTime()
        val notEnded = "($EFFECTIVE_END > $dayParam OR ($EFFECTIVE_END = $dayParam AND (e.end_time IS NULL OR e.end_time > :now)))"
        val grace =
            if (now.toLocalTime() >= GRACE_ENDS) {
                ""
            } else {
                params["graceDay"] = day.minusDays(1)
                " OR (e.end_date IS NULL AND e.event_date = :graceDay AND $EFFECTIVE_START >= TIME '$LATE_START')"
            }
        return "($notEnded$grace)"
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
     * Applies the many-to-many filters as `EXISTS` subqueries from one template parameterized by
     * [Association]; each correlates on `event_id = e.id`, so it tests membership without
     * multiplying the outer rows.
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
        // The family lives on the tag, so this is the genre EXISTS testing `family` in place of `slug`.
        // Beside `genre=` it narrows, never widens.
        filter.familySlug?.takeIf { it.isNotBlank() }?.let {
            conditions += Association.GENRE.existsClause(column = "family", param = "familySlug")
            params["familySlug"] = it.trim()
        }
    }

    /**
     * Applies the price bounds and the free-text title/subtitle search. Price bounds filter on
     * `COALESCE(price_presale, price_box_office)`; an event whose price is entirely unknown does
     * not satisfy a "min €X" filter.
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
            params["q"] = "%${it.trim().escapeLike()}%"
        }
    }

    private fun DatabaseClient.GenericExecuteSpec.bindAll(params: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
        params.entries.fold(this) { spec, (key, value) -> spec.bind(key, value) }

    /**
     * Builds a safe `ORDER BY` by whitelisting sort properties to known columns. Falls back to
     * chronological ordering; every order ends in [TIEBREAK], which keeps pagination deterministic.
     */
    private fun orderBy(pageable: Pageable): String {
        val clauses =
            pageable.sort.toList().flatMap { order ->
                SORT_COLUMNS[order.property]
                    ?.let { column ->
                        listOfNotNull("$column ${if (order.isAscending) "ASC" else "DESC"}", SECONDARY_SORT[column])
                    }.orEmpty()
            }
        return if (clauses.isEmpty()) DEFAULT_ORDER else "ORDER BY ${clauses.joinToString(", ")}, $TIEBREAK"
    }

    /** Today's date on [clock], as the `:seed` every ordered query binds — see [TIEBREAK]. */
    private fun tiebreakSeed(): String = LocalDate.now(clock).toString()

    /**
     * The three many-to-many associations filterable by slug: join table and referenced entity
     * table, so [existsClause] renders a correlated `EXISTS` from one template.
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

        /** Membership test on the referenced entity's [column] — `slug` unless a caller says otherwise. */
        fun existsClause(
            column: String = "slug",
            param: String = this.param
        ): String =
            "EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.$joinTable $joinAlias " +
                "JOIN $EVENTS_SCHEMA.$refTable $refAlias ON $refAlias.id = $joinAlias.$foreignKey " +
                "WHERE $joinAlias.event_id = e.id AND $refAlias.$column = :$param)"
    }

    companion object {
        /** The day an event is over after: its stated end, else its date (ADR-029). */
        private const val EFFECTIVE_END = "COALESCE(e.end_date, e.event_date)"

        /** An event starting at or after this is a night, and gets the grace (#299). */
        private const val LATE_START = "22:00"

        /** When last night is over for the listing (#299). Shared with the frontend's `isPastEvent`. */
        private val GRACE_ENDS: LocalTime = LocalTime.of(6, 0)

        /**
         * The time an event sorts by: its start, else its doors, else the slot its kind of event usually
         * takes ([AssumedStartTime], #1384). Never null, so a timeless club night lands among the nights.
         */
        private val EFFECTIVE_START = AssumedStartTime.SQL_EFFECTIVE_START

        private val SORT_COLUMNS =
            mapOf(
                "eventDate" to "e.event_date",
                "startTime" to EFFECTIVE_START,
                "title" to "e.title",
                "pricePresale" to "e.price_presale"
            )

        /** Stable within-day ordering applied alongside a date sort, so same-day events keep chronological order. */
        private val START_TIME_TIEBREAKER = "$EFFECTIVE_START ASC"

        /** Extra ordering appended after a primary sort column, keyed by that column. */
        private val SECONDARY_SORT = mapOf("e.event_date" to START_TIME_TIEBREAKER)

        /**
         * How rows that tie on every requested key are ordered: by a hash of the id and the day's date,
         * then the id (#1380). Club nights cluster at 23:00 and 00:00, so the decider was the id, the
         * venue that announced earliest, and the same venues led the home page every day. The hash
         * rotates the order inside a tie once a day while keeping it the same for every visitor and
         * every page; `random()` would hand two visitors two orders, let page 2 repeat page 1, and leave
         * the response cache serving whichever it computed first. The trailing `e.id` makes the order
         * total.
         */
        private const val TIEBREAK = "md5(e.id::text || :seed) ASC, e.id ASC"
        private val DEFAULT_ORDER = "ORDER BY e.event_date ASC, $START_TIME_TIEBREAKER, $TIEBREAK"
    }
}

/**
 * Reads the `e.id` the id projections select, never null. A null means the `SELECT` and this
 * mapping have drifted, and the message says so rather than a bare `NullPointerException`.
 */
private fun Readable.requiredEventId(): Long = requireNotNull(get(0, Long::class.javaObjectType)) { "Event id projection returned a null id" }
