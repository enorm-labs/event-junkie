package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Query
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

    /**
     * The same count as [countByEventDateGreaterThanEqual], **broken down by source** — the
     * `importer.source.events_future` gauge (#700).
     *
     * The aggregate above is the catalogue seen from one level up, and one venue of eighty-six going
     * silent moves it by a rounding error. The failure #415 was written about is a single scraper:
     * the venue redesigns its site, the request still returns 200, the run still reports success,
     * and that one source writes nothing for a fortnight.
     *
     * **A source holding nothing produces no row**, which is the trap #618 already paid for once —
     * absence looks exactly like health. [de.norm.events.scraper.MetricsRefreshService] therefore
     * publishes an explicit zero for every enabled source this query does not return.
     *
     * `event_source_id IS NOT NULL` drops events created by hand through the admin API: no source to
     * tag them with, and no scraper the number could say anything about.
     *
     * `GROUP BY` has no derived form (ADR-002), so this is raw SQL, schema-prefixed with the
     * interpolated constant rather than a literal (ADR-004, #540). **The column aliases are the
     * contract** — R2DBC maps a projection by label, nothing checks it at compile time, and
     * `PerSourceEventsGaugeIntegrationTest` is what runs this against a real database.
     */
    @Query(
        """
        SELECT e.event_source_id AS event_source_id, COUNT(*) AS future_events
        FROM $EVENTS_SCHEMA.event e
        WHERE e.event_date >= :date AND e.event_source_id IS NOT NULL
        GROUP BY e.event_source_id
        """
    )
    fun countFuturePerSource(date: LocalDate): Flow<SourceFutureEventsRow>

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
 * One row of [EventRepository.countFuturePerSource]: a source and how many future events it holds.
 *
 * `eventSourceId` is non-null because the query excludes the manual bucket, and both properties are
 * `Long` because Postgres' `COUNT` is `bigint` — mapping it to `Int` would work until the day it
 * did not.
 */
data class SourceFutureEventsRow(
    val eventSourceId: Long,
    val futureEvents: Long
)

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
