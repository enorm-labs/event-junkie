package de.norm.events.event

import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDate

/**
 * Reactive repository for [EventEntity] persistence via R2DBC.
 */
interface EventRepository : CoroutineCrudRepository<EventEntity, Long> {
    /** Batch-fetches events by their source IDs to avoid N+1 queries during upsert. */
    fun findBySourceIdIn(sourceIds: Collection<String>): Flow<EventEntity>

    /**
     * Events dated [date] or later — the `db.events.future` gauge (#415).
     *
     * **A future count trending to zero is a broken pipeline seen from the other end**, and it
     * catches what a per-source metric cannot: every importer can report success while the programme
     * as a whole quietly ages out, because "imported 0 new events" and "imported nothing because
     * there is nothing left to import" look identical one source at a time.
     *
     * Derived rather than a `@Query`: `countBy*` is one of the forms Spring Data R2DBC does derive
     * (ADR-002), so this needs no hand-written SQL and no schema prefix.
     */
    suspend fun countByEventDateGreaterThanEqual(date: LocalDate): Long

    /** Finds all events with pagination and sorting applied via [pageable]. */
    fun findAllBy(pageable: Pageable): Flow<EventEntity>

    /**
     * Finds events imported by a specific event source whose date falls within a given range.
     *
     * Used to detect stale events that were previously imported but are no longer listed
     * on the source website. The [eventSourceId] FK directly identifies the source,
     * and the date range limits the scope to events we actually scraped (avoiding deletion
     * of events on pages we didn't fetch).
     */
    fun findByEventSourceIdAndEventDateBetween(
        eventSourceId: Long,
        fromDate: LocalDate,
        toDate: LocalDate
    ): Flow<EventEntity>

    /**
     * Bulk-deletes events by their IDs (used for stale event cleanup).
     *
     * Associated join table rows (`event_artist`, `event_promoter`) are cleaned up
     * automatically via `ON DELETE CASCADE` in the schema.
     */
    suspend fun deleteByIdIn(ids: Collection<Long>)
}

/**
 * Repository for the `event_artist` join table.
 */
interface EventArtistRepository : CoroutineCrudRepository<EventArtistEntity, Long> {
    fun findByEventId(eventId: Long): Flow<EventArtistEntity>

    /** Batch-fetches artist associations for multiple events to avoid N+1 queries. */
    fun findByEventIdIn(eventIds: Collection<Long>): Flow<EventArtistEntity>

    suspend fun deleteByEventId(eventId: Long)
}

/**
 * Repository for the `event_promoter` join table.
 */
interface EventPromoterRepository : CoroutineCrudRepository<EventPromoterEntity, Long> {
    fun findByEventId(eventId: Long): Flow<EventPromoterEntity>

    /** Batch-fetches promoter associations for multiple events to avoid N+1 queries. */
    fun findByEventIdIn(eventIds: Collection<Long>): Flow<EventPromoterEntity>

    suspend fun deleteByEventId(eventId: Long)
}
