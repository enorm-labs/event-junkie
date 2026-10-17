package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDate

interface VenueRepository : CoroutineCrudRepository<VenueEntity, Long> {
    /** Finds a single venue by its unique slug, or null if not found. */
    suspend fun findBySlug(slug: String): VenueEntity?

    /** Batch-fetches venues by ID — used to resolve embedded venue summaries for an event page. */
    fun findByIdIn(ids: Collection<Long>): Flow<VenueEntity>

    /** Whether we show this venue's events: an `event_source` row points at it, or it has an event on or after [today] (#2766). */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_source s WHERE s.venue_id = :venueId) " +
            "OR EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.venue_id = :venueId AND e.event_date >= :today)"
    )
    suspend fun isImported(
        venueId: Long,
        today: LocalDate
    ): Boolean
}
