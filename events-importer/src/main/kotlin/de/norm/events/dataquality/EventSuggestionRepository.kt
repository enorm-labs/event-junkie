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

/** One proposed fix for one field of one event, with the evidence for it. */
data class EventSuggestionRow(
    val id: Long,
    val eventId: Long,
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

/**
 * The `event_suggestion` table.
 *
 * Hand-written SQL rather than a `CoroutineCrudRepository`, for two reasons. The list takes two
 * optional filters, and a derived query needs a method per combination. And a status change is one
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
        eventId: Long?,
        status: EventSuggestionStatus?,
        limit: Int,
        offset: Long
    ): List<EventSuggestionRow> =
        bindFilters(
            template.databaseClient.sql(
                """
                SELECT $COLUMNS
                FROM $EVENTS_SCHEMA.event_suggestion
                ${where(eventId, status)}
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """.trimIndent()
            ),
            eventId,
            status
        ).bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toSuggestion() }
            .flow()
            .toList()

    suspend fun count(
        eventId: Long?,
        status: EventSuggestionStatus?
    ): Long =
        bindFilters(
            template.databaseClient.sql(
                "SELECT count(*) AS total FROM $EVENTS_SCHEMA.event_suggestion ${where(eventId, status)}"
            ),
            eventId,
            status
        ).map { row, _ -> row.required("total", Number::class.java).toLong() }
            .one()
            .awaitSingle()

    suspend fun findById(id: Long): EventSuggestionRow? =
        template.databaseClient
            .sql("SELECT $COLUMNS FROM $EVENTS_SCHEMA.event_suggestion WHERE id = :id")
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

    private fun where(
        eventId: Long?,
        status: EventSuggestionStatus?
    ): String =
        listOfNotNull(
            "event_id = :eventId".takeIf { eventId != null },
            "status = :status".takeIf { status != null }
        ).takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ", prefix = "WHERE ")
            .orEmpty()

    private fun bindFilters(
        spec: DatabaseClient.GenericExecuteSpec,
        eventId: Long?,
        status: EventSuggestionStatus?
    ): DatabaseClient.GenericExecuteSpec {
        var bound = spec
        if (eventId != null) bound = bound.bind("eventId", eventId)
        if (status != null) bound = bound.bind("status", status.name)
        return bound
    }

    private fun Readable.toSuggestion(): EventSuggestionRow =
        EventSuggestionRow(
            id = required("id", Number::class.java).toLong(),
            eventId = required("event_id", Number::class.java).toLong(),
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
            "id, event_id, field, stored_value, proposed_value, evidence, confidence, check_name, model, status, created_at"
    }
}
