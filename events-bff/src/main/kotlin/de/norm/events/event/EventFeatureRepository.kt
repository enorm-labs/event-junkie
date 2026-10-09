package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * Reads `event_feature`, which the importer owns (#2631). Raw SQL, because the table's key is the
 * pair `(event_id, feature)` and Spring Data R2DBC maps no composite key.
 */
@Repository
class EventFeatureRepository(
    private val databaseClient: DatabaseClient
) {
    /** The feature slugs of the event [eventId], ordered by slug. */
    suspend fun findByEventId(eventId: Long): List<String> =
        databaseClient
            .sql("SELECT feature FROM $EVENTS_SCHEMA.event_feature WHERE event_id = :eventId ORDER BY feature")
            .bind("eventId", eventId)
            .map { row: Readable -> requireNotNull(row.get("feature", String::class.java)) { "event_feature.feature is NOT NULL" } }
            .all()
            .collectList()
            .awaitSingle()
}
