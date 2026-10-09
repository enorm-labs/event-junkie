package de.norm.events.importing

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository

/**
 * The fields of [eventId] that enrichment source [eventSourceId] filled, by their `PinnedField.key`,
 * and the page it read them from, which the event page credits (ADR-043, V131).
 */
data class EventEnrichment(
    val eventId: Long,
    val eventSourceId: Long,
    val sourceUrl: String,
    val fields: Set<String>
)

/**
 * The `event_enrichment` rows. Hand-written SQL, because the key is two columns and Spring Data
 * needs a single `@Id`, as for [EventQualityFlagRepository].
 */
@Repository
class EventEnrichmentRepository(
    private val template: R2dbcEntityTemplate
) {
    /** The records of [eventIds], in key order. */
    suspend fun findByEventIds(eventIds: Collection<Long>): List<EventEnrichment> =
        if (eventIds.isEmpty()) {
            emptyList()
        } else {
            template.databaseClient
                .sql(
                    "SELECT event_id, event_source_id, source_url, fields FROM $EVENTS_SCHEMA.event_enrichment " +
                        "WHERE event_id = ANY(:ids) ORDER BY event_id, event_source_id"
                ).bind("ids", eventIds.toTypedArray())
                .map { row, _ ->
                    EventEnrichment(
                        eventId = requireNotNull(row.get("event_id", Number::class.java)).toLong(),
                        eventSourceId = requireNotNull(row.get("event_source_id", Number::class.java)).toLong(),
                        sourceUrl = requireNotNull(row.get("source_url", String::class.java)),
                        fields = requireNotNull(row.get("fields", Array<String>::class.java)).toSet()
                    )
                }.all()
                .asFlow()
                .toList()
        }

    /**
     * Writes [record], adding its fields to the ones the source filled before and taking its page as
     * the credit. Fields are only ever added here; the main source's import takes them off ([release]).
     */
    suspend fun record(record: EventEnrichment) {
        if (record.fields.isEmpty()) return
        template.databaseClient
            .sql(
                "INSERT INTO $EVENTS_SCHEMA.event_enrichment AS e (event_id, event_source_id, source_url, fields) " +
                    "VALUES (:eventId, :eventSourceId, :sourceUrl, :fields) " +
                    "ON CONFLICT (event_id, event_source_id) DO UPDATE SET source_url = EXCLUDED.source_url, " +
                    "fields = ARRAY(SELECT DISTINCT f FROM unnest(e.fields || EXCLUDED.fields) AS f ORDER BY f)"
            ).bind("eventId", record.eventId)
            .bind("eventSourceId", record.eventSourceId)
            .bind("sourceUrl", record.sourceUrl)
            .bind("fields", record.fields.sorted().toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    /**
     * Takes [fields] off every record of [eventId]: the main source now sets them, and its value wins
     * (ADR-043). A record left with no field is deleted, so the page stops crediting that source.
     */
    suspend fun release(
        eventId: Long,
        fields: Set<String>
    ) {
        if (fields.isEmpty()) return
        val released = fields.toTypedArray()
        // The delete first: the CHECK refuses a record with no field, so the update must not empty one.
        template.databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.event_enrichment WHERE event_id = :eventId AND fields <@ :released")
            .bind("eventId", eventId)
            .bind("released", released)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        template.databaseClient
            .sql(
                "UPDATE $EVENTS_SCHEMA.event_enrichment SET fields = ARRAY(SELECT f FROM unnest(fields) AS f WHERE f <> ALL(:released) ORDER BY f) " +
                    "WHERE event_id = :eventId AND fields && :released"
            ).bind("eventId", eventId)
            .bind("released", released)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }
}
