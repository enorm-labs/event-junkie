package de.norm.events.dataquality

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime

/** Where a suggestion stands. Only a person moves it out of [OPEN] (ADR-041 rule 3). */
enum class EventSuggestionStatus {
    OPEN,
    DISMISSED,
    ACCEPTED;

    companion object {
        fun byName(name: String): EventSuggestionStatus? = entries.firstOrNull { it.name == name }
    }
}

/** One proposed fix for one field of one event, with the evidence for it and the event it is for. */
data class EventSuggestionRow(
    val id: Long,
    val eventId: Long,
    val event: SuggestedEvent,
    val field: String,
    val storedValue: String?,
    val proposedValue: String,
    val evidence: String,
    val confidence: Double,
    val checkName: String,
    val model: String?,
    val status: EventSuggestionStatus,
    val createdAt: OffsetDateTime
)

/** The event a suggestion is for, as a reviewer recognises it. */
data class SuggestedEvent(
    val title: String,
    val slug: String,
    val eventDate: LocalDate,
    val venueName: String
)

/** The list filters. Each one that is null does not narrow the list. */
data class EventSuggestionFilter(
    val eventId: Long? = null,
    val status: EventSuggestionStatus? = null,
    val checkName: String? = null
)

/**
 * The `event_suggestion` table.
 *
 * Hand-written SQL rather than a `CoroutineCrudRepository`, for two reasons. The list takes three
 * optional filters and joins the event and its venue, and a derived query does neither. And a status change is one
 * `UPDATE` of one column: a mapped entity would write every column back, `created_at` included.
 *
 * The filter values are bound, never concatenated. Only the fixed `WHERE` fragments are.
 */
@Repository
class EventSuggestionRepository(
    private val template: R2dbcEntityTemplate
) {
    /**
     * One page, newest first, with `id` as the tie-break so a page boundary between two rows of the
     * same instant does not move on the next request.
     */
    suspend fun findPage(
        filter: EventSuggestionFilter,
        limit: Int,
        offset: Long
    ): List<EventSuggestionRow> =
        bindFilters(
            template.databaseClient.sql(
                """
                SELECT $COLUMNS
                FROM $FROM
                ${where(filter)}
                ORDER BY s.created_at DESC, s.id DESC
                LIMIT :limit OFFSET :offset
                """.trimIndent()
            ),
            filter
        ).bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toSuggestion() }
            .flow()
            .toList()

    suspend fun count(filter: EventSuggestionFilter): Long =
        bindFilters(
            template.databaseClient.sql(
                "SELECT count(*) AS total FROM $EVENTS_SCHEMA.event_suggestion s ${where(filter)}"
            ),
            filter
        ).map { row, _ -> row.required("total", Number::class.java).toLong() }
            .one()
            .awaitSingle()

    suspend fun findById(id: Long): EventSuggestionRow? =
        template.databaseClient
            .sql("SELECT $COLUMNS FROM $FROM WHERE s.id = :id")
            .bind("id", id)
            .map { row, _ -> row.toSuggestion() }
            .one()
            .awaitSingleOrNull()

    /**
     * Sets the status of one row and returns how many rows changed: 0 or 1.
     *
     * `onlyFrom` limits the change to rows in those states, so the caller can refuse to turn an
     * accepted suggestion into a dismissed one without a read-then-write race.
     */
    suspend fun updateStatus(
        id: Long,
        status: EventSuggestionStatus,
        onlyFrom: Set<EventSuggestionStatus>
    ): Long =
        template.databaseClient
            .sql(
                "UPDATE $EVENTS_SCHEMA.event_suggestion SET status = :status " +
                    "WHERE id = :id AND status = ANY(:from)"
            ).bind("status", status.name)
            .bind("id", id)
            .bind("from", onlyFrom.map { it.name }.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    private fun where(filter: EventSuggestionFilter): String =
        listOfNotNull(
            "s.event_id = :eventId".takeIf { filter.eventId != null },
            "s.status = :status".takeIf { filter.status != null },
            "s.check_name = :checkName".takeIf { filter.checkName != null }
        ).takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ", prefix = "WHERE ")
            .orEmpty()

    private fun bindFilters(
        spec: DatabaseClient.GenericExecuteSpec,
        filter: EventSuggestionFilter
    ): DatabaseClient.GenericExecuteSpec {
        var bound = spec
        filter.eventId?.let { bound = bound.bind("eventId", it) }
        filter.status?.let { bound = bound.bind("status", it.name) }
        filter.checkName?.let { bound = bound.bind("checkName", it) }
        return bound
    }

    private fun Readable.toSuggestion(): EventSuggestionRow =
        EventSuggestionRow(
            id = required("id", Number::class.java).toLong(),
            eventId = required("event_id", Number::class.java).toLong(),
            event =
                SuggestedEvent(
                    title = required("event_title", String::class.java),
                    slug = required("event_slug", String::class.java),
                    eventDate = required("event_date", LocalDate::class.java),
                    venueName = required("venue_name", String::class.java)
                ),
            field = required("field", String::class.java),
            storedValue = get("stored_value", String::class.java),
            proposedValue = required("proposed_value", String::class.java),
            evidence = required("evidence", String::class.java),
            confidence = required("confidence", Number::class.java).toDouble(),
            checkName = required("check_name", String::class.java),
            model = get("model", String::class.java),
            status = EventSuggestionStatus.valueOf(required("status", String::class.java)),
            createdAt = required("created_at", OffsetDateTime::class.java)
        )

    private fun <T : Any> Readable.required(
        column: String,
        type: Class<T>
    ): T = requireNotNull(get(column, type)) { "Column '$column' is missing from the event_suggestion projection" }

    private companion object {
        const val COLUMNS =
            "s.id, s.event_id, s.field, s.stored_value, s.proposed_value, s.evidence, s.confidence, s.check_name, s.model, " +
                "s.status, s.created_at, e.title AS event_title, e.slug AS event_slug, e.event_date, v.name AS venue_name"

        /** Inner joins lose no row: a suggestion needs its event, and an event its venue. */
        const val FROM =
            "$EVENTS_SCHEMA.event_suggestion s " +
                "JOIN $EVENTS_SCHEMA.event e ON e.id = s.event_id " +
                "JOIN $EVENTS_SCHEMA.venue v ON v.id = e.venue_id"
    }
}
