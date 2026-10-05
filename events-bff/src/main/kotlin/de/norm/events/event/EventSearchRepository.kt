package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.AssumedStartTime
import de.norm.events.common.TextSearch
import de.norm.events.common.countQuery
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
     * opened earlier is still in. The calendar sets it with [to] (#1405), and so does the list with
     * `running=true` (#2674). Wins over [from].
     */
    val runningFrom: LocalDate? = null,
    /**
     * A day the event is on: started by it and not ended before it (ADR-029). Internal, set by the
     * Tonight feed. Wins over [from] and [to].
     */
    val on: LocalDate? = null,
    /** Any of these types; empty imposes no constraint. [EventFilterParams] normalizes them. */
    val eventTypes: List<String> = emptyList(),
    val venueSlug: String? = null,
    /** Any of these districts; empty imposes no constraint. [EventFilterParams] normalizes them. */
    val districts: List<String> = emptyList(),
    /** Any of these venue types; empty imposes no constraint. [EventFilterParams] normalizes them. */
    val venueTypes: List<String> = emptyList(),
    val artistSlug: String? = null,
    val promoterSlug: String? = null,
    val genreSlug: String? = null,
    /** Any of these families; empty imposes no constraint. [EventFilterParams] normalizes them. */
    val familySlugs: List<String> = emptyList(),
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
@Suppress("TooManyFunctions") // One search in three shapes, a page, every row and the newest, over one shared WHERE builder.
class EventSearchRepository(
    private val databaseClient: DatabaseClient,
    private val clock: Clock
) {
    suspend fun search(
        filter: EventFilter,
        pageable: Pageable,
        countCap: Int? = null
    ): EventIdPage =
        TextSearch.strictThenSimilar(filter.query, found = { it.total > 0 }) { bySimilarity ->
            val params = mutableMapOf<String, Any>()
            val where = buildWhereClause(filter, params, bySimilarity)
            val total =
                databaseClient
                    .sql(countQuery("$EVENTS_SCHEMA.event e", where, countCap))
                    .bindAll(params)
                    .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                    .one()
                    .awaitSingle()
            if (total == 0L) {
                EventIdPage(emptyList(), 0L)
            } else {
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
                EventIdPage(ids, total)
            }
        }

    /**
     * Every matching event ID in default chronological order, unpaged, for the calendar view. Safe
     * because the caller bounds the range (`EventService.MAX_CALENDAR_DAYS`).
     */
    suspend fun searchAll(filter: EventFilter): List<Long> =
        TextSearch.strictThenSimilar(filter.query, found = { it.isNotEmpty() }) { bySimilarity ->
            val params = mutableMapOf<String, Any>()
            val where = buildWhereClause(filter, params, bySimilarity)
            databaseClient
                .sql("SELECT e.id FROM $EVENTS_SCHEMA.event e $where $DEFAULT_ORDER")
                .bindAll(params)
                .bind("seed", tiebreakSeed())
                .map { row: Readable -> row.requiredEventId() }
                .all()
                .collectList()
                .awaitSingle()
        }

    /**
     * The [limit] matching event IDs the importer first stored most recently, newest first, for the
     * event feed. `created_at` is that moment, because an update keeps it. Ties fall to the higher
     * id, the later insert, so the order is the same on every request.
     */
    suspend fun newest(
        filter: EventFilter,
        limit: Int
    ): List<Long> =
        TextSearch.strictThenSimilar(filter.query, found = { it.isNotEmpty() }) { bySimilarity ->
            val params = mutableMapOf<String, Any>()
            val where = buildWhereClause(filter, params, bySimilarity)
            databaseClient
                .sql("SELECT e.id FROM $EVENTS_SCHEMA.event e $where ORDER BY e.created_at DESC, e.id DESC LIMIT :limit")
                .bindAll(params)
                .bind("limit", limit)
                .map { row: Readable -> row.requiredEventId() }
                .all()
                .collectList()
                .awaitSingle()
        }

    /**
     * Up to [limit] events that are not over and share something with the event [eventId] at
     * [venueId], best match first (#359). An event scores [ARTIST_WEIGHT] per shared artist,
     * [VENUE_WEIGHT] for the same venue and [GENRE_WEIGHT] per shared genre tag. Each branch of the
     * union starts from an index on its join column, so no branch reads the whole table. Ties fall
     * to the earliest date, then to the list's own order.
     */
    suspend fun related(
        eventId: Long,
        venueId: Long,
        limit: Int
    ): List<Long> {
        val params = mutableMapOf<String, Any>("eventId" to eventId, "venueId" to venueId)
        val where = buildWhereClause(EventFilter(), params, bySimilarity = false)
        val matches =
            listOf(
                "SELECT other.event_id AS id, $ARTIST_WEIGHT AS weight FROM $EVENTS_SCHEMA.event_artist own " +
                    "JOIN $EVENTS_SCHEMA.event_artist other ON other.artist_id = own.artist_id WHERE own.event_id = :eventId",
                "SELECT x.id, $VENUE_WEIGHT FROM $EVENTS_SCHEMA.event x WHERE x.venue_id = :venueId",
                "SELECT other.event_id, $GENRE_WEIGHT FROM $EVENTS_SCHEMA.event_genre_tag own " +
                    "JOIN $EVENTS_SCHEMA.event_genre_tag other ON other.genre_tag_id = own.genre_tag_id WHERE own.event_id = :eventId"
            ).joinToString(" UNION ALL ")
        // Grouped by the primary key, so the ORDER BY may read the row's other columns.
        return databaseClient
            .sql(
                "SELECT e.id FROM ($matches) m JOIN $EVENTS_SCHEMA.event e ON e.id = m.id $where AND e.id <> :eventId " +
                    "GROUP BY e.id ORDER BY SUM(m.weight) DESC, e.event_date ASC, $START_TIME_TIEBREAKER, $TIEBREAK LIMIT :limit"
            ).bindAll(params)
            .bind("seed", tiebreakSeed())
            .bind("limit", limit)
            .map { row: Readable -> row.requiredEventId() }
            .all()
            .collectList()
            .awaitSingle()
    }

    /** Assembles the `WHERE` clause for the present filters, registering bound values in [params]. */
    private fun buildWhereClause(
        filter: EventFilter,
        params: MutableMap<String, Any>,
        bySimilarity: Boolean
    ): String {
        val conditions = mutableListOf<String>()
        appendDateRange(filter, conditions, params)
        appendColumnFilters(filter, conditions, params)
        appendAssociationFilters(filter, conditions, params)
        appendPriceAndQuery(filter, conditions, params, bySimilarity)
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

    /** Applies filters on columns of the `event` table and its venue (type, venue, district, venue type). */
    private fun appendColumnFilters(
        filter: EventFilter,
        conditions: MutableList<String>,
        params: MutableMap<String, Any>
    ) {
        if (filter.eventTypes.isNotEmpty()) {
            conditions += "e.event_type IN (:eventTypes)"
            params["eventTypes"] = filter.eventTypes
        }
        filter.venueSlug?.takeIf { it.isNotBlank() }?.let {
            conditions += "e.venue_id IN (SELECT id FROM $EVENTS_SCHEMA.venue WHERE slug = :venueSlug)"
            params["venueSlug"] = it.trim()
        }
        if (filter.districts.isNotEmpty()) {
            conditions += "e.venue_id IN (SELECT id FROM $EVENTS_SCHEMA.venue WHERE district IN (:districts))"
            params["districts"] = filter.districts
        }
        // `&&` is array overlap, served by the GIN index on `venue_types`.
        if (filter.venueTypes.isNotEmpty()) {
            conditions += "e.venue_id IN (SELECT id FROM $EVENTS_SCHEMA.venue WHERE venue_types && :venueTypes)"
            params["venueTypes"] = filter.venueTypes.toTypedArray()
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
        if (filter.familySlugs.isNotEmpty()) {
            conditions += Association.GENRE.existsClause(column = "family", param = "familySlugs")
            params["familySlugs"] = filter.familySlugs
        }
    }

    /**
     * Applies the price bounds and the free-text search. Price bounds filter on
     * `COALESCE(price_presale, price_box_office)`; an event whose price is entirely unknown does
     * not satisfy a "min €X" filter. The search ([TextSearch]) reads the title, the subtitle, the
     * venue's name, the lineup's names and the promoters' names, so `ÆDEN` finds that venue's nights.
     */
    private fun appendPriceAndQuery(
        filter: EventFilter,
        conditions: MutableList<String>,
        params: MutableMap<String, Any>,
        bySimilarity: Boolean
    ) {
        filter.minPrice?.let {
            conditions += "COALESCE(e.price_presale, e.price_box_office) >= :minPrice"
            params["minPrice"] = it
        }
        filter.maxPrice?.let {
            conditions += "COALESCE(e.price_presale, e.price_box_office) <= :maxPrice"
            params["maxPrice"] = it
        }
        TextSearch.term(filter.query)?.let { term ->
            // One branch per searched column, so each reaches its own trigram index (V096). The same
            // columns as an `OR` on `e` read every upcoming event (#2533).
            val match = { column: String -> TextSearch.predicate(column, term, bySimilarity) }
            conditions +=
                listOf(
                    "SELECT x.id FROM $EVENTS_SCHEMA.event x WHERE ${match("x.title")}",
                    "SELECT x.id FROM $EVENTS_SCHEMA.event x WHERE ${match("x.subtitle")}",
                    "SELECT x.id FROM $EVENTS_SCHEMA.event x JOIN $EVENTS_SCHEMA.venue v ON v.id = x.venue_id WHERE ${match("v.name")}",
                    "SELECT ea.event_id FROM $EVENTS_SCHEMA.event_artist ea " +
                        "JOIN $EVENTS_SCHEMA.artist a ON a.id = ea.artist_id WHERE ${match("a.name")}",
                    "SELECT ep.event_id FROM $EVENTS_SCHEMA.event_promoter ep " +
                        "JOIN $EVENTS_SCHEMA.promoter p ON p.id = ep.promoter_id WHERE ${match("p.name")}"
                ).joinToString(" UNION ", prefix = "e.id IN (", postfix = ")")
            params += TextSearch.params(term, bySimilarity)
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

        /**
         * Membership test on the referenced entity's [column] — `slug` unless a caller says otherwise.
         * `IN` takes a single bound value or a list.
         */
        fun existsClause(
            column: String = "slug",
            param: String = this.param
        ): String = existsWhere(column) { "$it IN (:$param)" }

        /** An `EXISTS` over the associated rows whose [column], qualified, satisfies [predicate]. */
        fun existsWhere(
            column: String,
            predicate: (String) -> String
        ): String =
            "EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.$joinTable $joinAlias " +
                "JOIN $EVENTS_SCHEMA.$refTable $refAlias ON $refAlias.id = $joinAlias.$foreignKey " +
                "WHERE $joinAlias.event_id = e.id AND ${predicate("$refAlias.$column")})"
    }

    companion object {
        /** The day an event is over after: its stated end, else its date (ADR-029). */
        private const val EFFECTIVE_END = "COALESCE(e.end_date, e.event_date)"

        /** What one shared artist, the shared venue and one shared genre tag add to a related event's score. */
        private const val ARTIST_WEIGHT = 3
        private const val VENUE_WEIGHT = 2
        private const val GENRE_WEIGHT = 1

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
