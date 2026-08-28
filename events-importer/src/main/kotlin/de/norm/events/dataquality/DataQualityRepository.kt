package de.norm.events.dataquality

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.event.EventEntity
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * The measurements, as raw SQL, because R2DBC derives none of this.
 *
 * `GROUP BY` and conditional counts have no derived form, so these are `@Query` — schema-prefixed
 * because raw SQL bypasses both `@Table` and the `NamingStrategy` (ADR-004).
 *
 * **One pass, not seven.** Postgres' `COUNT(*) FILTER (WHERE …)` computes every completeness metric
 * in a single scan of `event`, with correlated `NOT EXISTS` for the two that depend on a join table.
 * Seven separate counting queries would be the obvious shape and would scan the table seven times
 * for a number that is only ever read together.
 *
 * **The column aliases are the contract.** R2DBC maps a projection by column *label*, so every
 * expression below is aliased in `snake_case` to match the constructor properties of
 * [SourceQualityRow]. Nothing checks that at compile time and a mismatch surfaces as a null or a
 * mapping exception at runtime, which is why `DataQualityReportIntegrationTest` exists to prove it.
 */
interface DataQualityRepository : CoroutineCrudRepository<EventEntity, Long> {
    /**
     * Every §1 metric, per source, in one scan.
     *
     * `event_source_id` is nullable and stays that way here: an event created by hand through the
     * admin API has no source, and those are reported under a synthetic `manual` bucket rather than
     * dropped. A `GROUP BY` over a nullable column gives them their own row for free — the
     * alternative, filtering them out, would make the report's own total disagree with the table's.
     */
    @Query(
        """
        SELECT
            e.event_source_id                                              AS event_source_id,
            COUNT(*)                                                       AS total_events,
            COUNT(*) FILTER (WHERE e.event_type = 'CONCERT'
                AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea WHERE ea.event_id = e.id))
                                                                           AS concerts_without_artist,
            COUNT(*) FILTER (WHERE e.event_type = 'OTHER')                 AS events_typed_other,
            COUNT(*) FILTER (WHERE e.genre IS NULL OR e.genre = '')        AS missing_genre,
            COUNT(*) FILTER (WHERE NOT EXISTS
                (SELECT 1 FROM $EVENTS_SCHEMA.event_promoter ep WHERE ep.event_id = e.id))
                                                                           AS missing_promoter,
            COUNT(*) FILTER (WHERE e.price_presale IS NULL AND e.price_box_office IS NULL
                AND e.free = false AND e.price_note IS NULL)               AS missing_price,
            COUNT(*) FILTER (WHERE e.start_time IS NULL)                   AS missing_start_time,
            COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_source es
                WHERE es.id = e.event_source_id AND es.licence_reviewed_at IS NULL))
                                                                           AS unreviewed_licence
        FROM $EVENTS_SCHEMA.event e
        GROUP BY e.event_source_id
        """
    )
    fun aggregatePerSource(): Flow<SourceQualityRow>

    /**
     * Distinct artist names linked to each source's events, for the one metric SQL cannot express.
     *
     * `isNonArtistName` is Kotlin — a curated vocabulary of placeholders, role labels and support-act
     * boilerplate — so the filtering happens in the service and this query's only job is to hand it
     * the pairs without an N+1. `DISTINCT` because a name that appears on forty events is one
     * suspect name, not forty.
     */
    @Query(
        """
        SELECT DISTINCT e.event_source_id AS event_source_id, a.name AS artist_name
        FROM $EVENTS_SCHEMA.event e
        JOIN $EVENTS_SCHEMA.event_artist ea ON ea.event_id = e.id
        JOIN $EVENTS_SCHEMA.artist a ON a.id = ea.artist_id
        """
    )
    fun artistNamesPerSource(): Flow<SourceArtistNameRow>
}

/**
 * One row of [DataQualityRepository.aggregatePerSource].
 *
 * `eventSourceId` is nullable and means the `manual` bucket; every count is a `Long` because
 * Postgres' `COUNT` is `bigint` and mapping it to `Int` would work until the day it did not.
 */
data class SourceQualityRow(
    val eventSourceId: Long?,
    val totalEvents: Long,
    val concertsWithoutArtist: Long,
    val eventsTypedOther: Long,
    val missingGenre: Long,
    val missingPromoter: Long,
    val missingPrice: Long,
    val missingStartTime: Long,
    val unreviewedLicence: Long
)

/** One `(source, artist name)` pair — see [DataQualityRepository.artistNamesPerSource]. */
data class SourceArtistNameRow(
    val eventSourceId: Long?,
    val artistName: String
)
