package de.norm.events.transit

import de.norm.events.EVENTS_SCHEMA
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.stereotype.Repository
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

/** Writes `transit_stop` (V231). The BFF reads it; nothing else in the importer does. */
@Repository
class TransitStopStore(
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator
) {
    /** Replaces every row with [stations] in one transaction, so a reader sees the old set or the new one. */
    suspend fun replaceAll(
        stations: List<TransitStation>,
        importedAt: Instant
    ) {
        transactionalOperator.executeAndAwait {
            databaseClient.sql("DELETE FROM $EVENTS_SCHEMA.transit_stop").await()
            stations.chunked(BATCH_SIZE).forEach { chunk -> insert(chunk, importedAt) }
        }
    }

    /** One statement per chunk: the lines travel joined by `|`, which no line name holds, because `unnest` flattens a 2-D array. */
    private suspend fun insert(
        chunk: List<TransitStation>,
        importedAt: Instant
    ) {
        databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.transit_stop (gtfs_stop_id, name, latitude, longitude, lines, imported_at)
                SELECT s.id, s.name, s.latitude, s.longitude, string_to_array(s.lines, '$LINE_SEPARATOR'), :importedAt
                FROM unnest(:ids::text[], :names::text[], :latitudes::numeric[], :longitudes::numeric[], :lines::text[])
                    AS s(id, name, latitude, longitude, lines)
                """.trimIndent()
            ).bind("ids", chunk.map { it.gtfsStopId }.toTypedArray())
            .bind("names", chunk.map { it.name }.toTypedArray())
            .bind("latitudes", chunk.map { it.latitude }.toTypedArray())
            .bind("longitudes", chunk.map { it.longitude }.toTypedArray())
            .bind("lines", chunk.map { station -> station.lines.joinToString(LINE_SEPARATOR) }.toTypedArray())
            .bind("importedAt", importedAt)
            .await()
    }

    companion object {
        private const val BATCH_SIZE = 500
        private const val LINE_SEPARATOR = "|"
    }
}
