package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * Derives `venue.programme_families` and `venue.programme_event_types` from the venue's own events (#327).
 *
 * Raw SQL over the event tables, so the venue module still depends on no other. A value counts when at
 * least [MIN_SHARE] of the window's events and at least [MIN_EVENTS] events carry it; the top [MAX_VALUES]
 * are kept, most frequent first. `OTHER` is never an event type a visitor can choose by, so it never counts.
 */
@Repository
class VenueProgrammeStore(
    private val databaseClient: DatabaseClient
) {
    /** Recomputes every venue and returns how many rows changed. */
    suspend fun refreshAll(since: LocalDate): Long = refresh(since, venueId = null)

    /** Recomputes one venue and returns 1 when its row changed, else 0. */
    suspend fun refresh(
        venueId: Long,
        since: LocalDate
    ): Long = refresh(since, venueId as Long?)

    private suspend fun refresh(
        since: LocalDate,
        venueId: Long?
    ): Long {
        // Writes only a changed row, so the updated_at trigger fires only on a real change.
        val spec =
            databaseClient
                .sql(programmeUpdate(oneVenue = venueId != null))
                .bind("since", since)
                .bind("minEvents", MIN_EVENTS)
                .bind("minShare", MIN_SHARE)
                .bind("maxValues", MAX_VALUES)
        return (if (venueId == null) spec else spec.bind("venueId", venueId)).fetch().rowsUpdated().awaitSingle()
    }

    /** One UPDATE that derives both arrays, for every venue or for the one bound as `:venueId`. */
    private fun programmeUpdate(oneVenue: Boolean): String {
        val eventFilter = if (oneVenue) "AND e.venue_id = :venueId" else ""
        val venueFilter = if (oneVenue) "WHERE v.id = :venueId" else ""
        return """
            WITH win AS (
                SELECT e.id, e.venue_id, e.event_type FROM $EVENTS_SCHEMA.event e
                WHERE e.event_date >= :since AND e.status <> 'CANCELLED' $eventFilter
            ),
            fam AS (
                SELECT w.venue_id, gt.family AS val, count(DISTINCT w.id) AS n
                FROM win w
                JOIN $EVENTS_SCHEMA.event_genre_tag egt ON egt.event_id = w.id
                JOIN $EVENTS_SCHEMA.genre_tag gt ON gt.id = egt.genre_tag_id
                WHERE gt.family IS NOT NULL
                GROUP BY w.venue_id, gt.family
            ),
            fam_total AS (
                SELECT w.venue_id, count(DISTINCT w.id) AS n
                FROM win w
                JOIN $EVENTS_SCHEMA.event_genre_tag egt ON egt.event_id = w.id
                JOIN $EVENTS_SCHEMA.genre_tag gt ON gt.id = egt.genre_tag_id
                WHERE gt.family IS NOT NULL
                GROUP BY w.venue_id
            ),
            typ AS (
                SELECT venue_id, event_type AS val, count(*) AS n FROM win WHERE event_type <> 'OTHER' GROUP BY venue_id, event_type
            ),
            typ_total AS (
                SELECT venue_id, count(*) AS n FROM win GROUP BY venue_id
            ),
            ranked AS (
                SELECT 'family' AS kind, f.venue_id, f.val,
                       row_number() OVER (PARTITION BY f.venue_id ORDER BY f.n DESC, f.val) AS r
                FROM fam f JOIN fam_total t USING (venue_id)
                WHERE f.n >= :minEvents AND f.n >= :minShare * t.n
                UNION ALL
                SELECT 'type', y.venue_id, y.val,
                       row_number() OVER (PARTITION BY y.venue_id ORDER BY y.n DESC, y.val)
                FROM typ y JOIN typ_total t USING (venue_id)
                WHERE y.n >= :minEvents AND y.n >= :minShare * t.n
            ),
            computed AS (
                SELECT v.id,
                       coalesce(array_agg(r.val ORDER BY r.r) FILTER (WHERE r.kind = 'family'), '{}') AS families,
                       coalesce(array_agg(r.val ORDER BY r.r) FILTER (WHERE r.kind = 'type'), '{}') AS types
                FROM $EVENTS_SCHEMA.venue v
                LEFT JOIN ranked r ON r.venue_id = v.id AND r.r <= :maxValues
                $venueFilter
                GROUP BY v.id
            )
            UPDATE $EVENTS_SCHEMA.venue v
            SET programme_families = c.families, programme_event_types = c.types
            FROM computed c
            WHERE v.id = c.id
              AND (v.programme_families IS DISTINCT FROM c.families OR v.programme_event_types IS DISTINCT FROM c.types)
            """.trimIndent()
    }

    companion object {
        /** How far back the window reaches. Past events are never deleted, so the window has to end somewhere. */
        const val WINDOW_DAYS = 365L
        const val MIN_SHARE = 0.15
        const val MIN_EVENTS = 3
        const val MAX_VALUES = 3
    }
}
