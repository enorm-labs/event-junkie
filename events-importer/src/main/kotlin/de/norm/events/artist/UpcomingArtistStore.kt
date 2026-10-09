package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * Which artists an event bills soon, for the admin's review of MusicBrainz verdicts (#2946). A class,
 * not a derived query, because `ArtistRepository` is at detekt's function cap.
 */
@Repository
class UpcomingArtistStore(
    private val databaseClient: DatabaseClient
) {
    /**
     * The ids of the artists billed on an event that takes place on a day from [first] to [last]. A
     * festival counts on every day up to its `end_date`. A cancelled event and a postponed one, whose
     * date will not happen, do not count.
     */
    suspend fun idsBilledBetween(
        first: LocalDate,
        last: LocalDate
    ): List<Long> =
        databaseClient
            .sql(
                """
                SELECT DISTINCT ea.artist_id FROM $EVENTS_SCHEMA.event_artist ea
                JOIN $EVENTS_SCHEMA.event e ON e.id = ea.event_id
                WHERE e.event_date <= :last AND COALESCE(e.end_date, e.event_date) >= :first
                  AND e.status NOT IN ('CANCELLED', 'POSTPONED')
                """.trimIndent()
            ).bind("first", first)
            .bind("last", last)
            .map { row, _ -> requireNotNull(row.get("artist_id", Number::class.java)).toLong() }
            .all()
            .asFlow()
            .toList()
}
