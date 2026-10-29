package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Writes `event_change`: what moved on an existing event, for the event page (#2725). The importer
 * calls it for each row it updates, and for a removed row that an insert replaces with a new date
 * (#3067). The admin edit calls it for the row it saves.
 */
@Service
class EventChangeLog(
    private val template: R2dbcEntityTemplate
) {
    /**
     * Stores one row per tracked field that differs between each stored row and the row written over it.
     *
     * @param pairs each stored row beside the row written over it.
     * @param previousEventIds for a date move across a re-keyed source id, the removed event's id by the
     * inserted one's (#4139). The BFF moves the account rows on it to the event that stays.
     * @return how many changes were stored.
     */
    suspend fun record(
        pairs: Collection<Pair<EventEntity, EventEntity>>,
        previousEventIds: Map<Long, Long> = emptyMap()
    ): Int {
        val rows = pairs.flatMap { (stored, written) -> changesBetween(stored, written) }
        if (rows.isEmpty()) return 0
        template.databaseClient
            .sql(
                "INSERT INTO $EVENTS_SCHEMA.event_change (event_id, field, old_value, new_value, previous_event_id) " +
                    "SELECT * FROM unnest(:ids::bigint[], :fields::text[], :olds::text[], :news::text[], :previous::bigint[])"
            ).bind("ids", rows.map { it.eventId }.toTypedArray())
            .bind("fields", rows.map { it.field.name }.toTypedArray())
            .bind("olds", rows.map { it.oldValue }.toTypedArray())
            .bind("news", rows.map { it.newValue }.toTypedArray())
            .bind("previous", rows.map { previousEventIds[it.eventId] }.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        return rows.size
    }

    /**
     * Deletes the changes of one source's events that ended before [today], and the changes older
     * than [KEPT_DAYS] days, which the page no longer shows. Scoped to one source, so parallel imports
     * delete disjoint rows.
     */
    suspend fun prune(
        eventSourceId: Long,
        today: LocalDate
    ) {
        template.databaseClient
            .sql(
                "DELETE FROM $EVENTS_SCHEMA.event_change c USING $EVENTS_SCHEMA.event e " +
                    "WHERE e.id = c.event_id AND e.event_source_id = :source " +
                    "AND (COALESCE(e.end_date, e.event_date) < :today OR c.seen_at < now() - make_interval(days => :days))"
            ).bind("source", eventSourceId)
            .bind("today", today)
            .bind("days", KEPT_DAYS)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    companion object {
        /** How long the event page shows a change. The BFF reads the same window. */
        const val KEPT_DAYS = 14
    }
}

/** One row of `event_change`. */
data class EventChange(
    val eventId: Long,
    val field: EventChangeField,
    val oldValue: String,
    val newValue: String
)

/**
 * The tracked fields that differ between [stored] and [written]. A null on either side is not a
 * change: a value that disappears is a scrape that missed it, and one that appears was not published
 * before. Neither is a move a visitor has to know about.
 */
private fun changesBetween(
    stored: EventEntity,
    written: EventEntity
): List<EventChange> {
    val eventId = stored.id ?: return emptyList()
    return TRACKED.mapNotNull { (field, valueOf) ->
        val old = valueOf(stored)
        val new = valueOf(written)
        if (old == null || new == null || old == new) null else EventChange(eventId, field, old, new)
    }
}

private val TRACKED: List<Pair<EventChangeField, (EventEntity) -> String?>> =
    listOf(
        EventChangeField.EVENT_DATE to { it.eventDate.toString() },
        EventChangeField.START_TIME to { it.startTime?.toString() },
        EventChangeField.END_DATE to { it.endDate?.toString() },
        EventChangeField.END_TIME to { it.endTime?.toString() },
        EventChangeField.STATUS to { it.status },
        EventChangeField.VENUE to { it.venueId.toString() }
    )
