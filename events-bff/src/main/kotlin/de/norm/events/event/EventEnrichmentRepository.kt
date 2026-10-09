package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * Reads `event_enrichment`, which the importer owns (ADR-043, V131): which fields of an event an
 * enrichment source filled, and the page it read them from. Raw SQL, because the table's key is the
 * pair `(event_id, event_source_id)` and Spring Data R2DBC maps no composite key.
 */
@Repository
class EventEnrichmentRepository(
    private val databaseClient: DatabaseClient
) {
    /** The enrichment sources of event [eventId], in the order their sources were added. Empty for almost every event. */
    suspend fun findByEventId(eventId: Long): List<EnrichmentSourceResponse> =
        databaseClient
            .sql(
                "SELECT source_url, fields FROM $EVENTS_SCHEMA.event_enrichment " +
                    "WHERE event_id = :eventId ORDER BY event_source_id"
            ).bind("eventId", eventId)
            .map { row: Readable ->
                EnrichmentSourceResponse(
                    sourceUrl = requireNotNull(row.get("source_url", String::class.java)) { "event_enrichment.source_url is NOT NULL" },
                    fields = requireNotNull(row.get("fields", Array<String>::class.java)) { "event_enrichment.fields is NOT NULL" }.sorted()
                )
            }.all()
            .collectList()
            .awaitSingle()
}
