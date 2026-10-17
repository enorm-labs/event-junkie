package de.norm.events.festival

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * How many events link to each festival (#3273). Raw SQL on `event.festival_id`, so this module
 * reads the column without depending on the event module, which depends on this one.
 */
@Repository
class FestivalEventCounts(
    private val databaseClient: DatabaseClient
) {
    /** The count per festival of [festivalIds]. A festival with no event is missing from the map. */
    suspend fun of(festivalIds: Collection<Long>): Map<Long, Long> {
        if (festivalIds.isEmpty()) return emptyMap()
        return databaseClient
            .sql("SELECT festival_id, count(*) AS n FROM $EVENTS_SCHEMA.event WHERE festival_id IN (:ids) GROUP BY festival_id")
            .bind("ids", festivalIds)
            .map { row: Readable -> requireNotNull(row.get("festival_id", Long::class.javaObjectType)) to (row.get("n", Long::class.javaObjectType) ?: 0L) }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()
    }
}
