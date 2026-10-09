package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Reads `event_change`, which the importer writes when a date, time, status or venue of an event
 * moves (#2725).
 */
@Repository
class EventChangeRepository(
    private val databaseClient: DatabaseClient,
    private val clock: Clock
) {
    /**
     * The changes of event [eventId] from the last [KEPT_DAYS] days, newest first. A venue change
     * carries the venue names; a venue deleted since reads as null and is left out.
     */
    suspend fun recent(eventId: Long): List<EventChangeResponse> =
        databaseClient
            .sql(
                """
                SELECT c.field, c.old_value, c.new_value, c.seen_at, ov.name AS old_venue, nv.name AS new_venue
                FROM $EVENTS_SCHEMA.event_change c
                LEFT JOIN $EVENTS_SCHEMA.venue ov ON ov.id = CASE WHEN c.field = 'VENUE' THEN c.old_value::bigint END
                LEFT JOIN $EVENTS_SCHEMA.venue nv ON nv.id = CASE WHEN c.field = 'VENUE' THEN c.new_value::bigint END
                WHERE c.event_id = :eventId AND c.seen_at >= :since
                ORDER BY c.seen_at DESC, c.id DESC
                """.trimIndent()
            ).bind("eventId", eventId)
            .bind("since", OffsetDateTime.now(clock).minus(Duration.ofDays(KEPT_DAYS)))
            .map { row: Readable -> listOfNotNull(row.toResponse()) }
            .all()
            .collectList()
            .awaitSingle()
            .flatten()

    private fun Readable.toResponse(): EventChangeResponse? {
        val field = EventChangeField.valueOf(requireNotNull(get("field", String::class.java)))
        val (fromColumn, toColumn) = if (field == EventChangeField.VENUE) "old_venue" to "new_venue" else "old_value" to "new_value"
        val from = get(fromColumn, String::class.java)
        val to = get(toColumn, String::class.java)
        val seenAt = requireNotNull(get("seen_at", OffsetDateTime::class.java))
        return if (from == null || to == null) null else EventChangeResponse(field, from, to, seenAt.atZoneSameInstant(BERLIN).toOffsetDateTime())
    }

    private companion object {
        /** The importer prunes on the same window (`EventChangeLog.KEPT_DAYS`). */
        const val KEPT_DAYS = 14L
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
