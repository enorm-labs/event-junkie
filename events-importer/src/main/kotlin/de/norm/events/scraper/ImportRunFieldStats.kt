package de.norm.events.scraper

import kotlinx.coroutines.flow.Flow
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

/**
 * What one run extracted, for one field — the append-only row the baseline is derived from (#472).
 *
 * Both numbers are stored and never a ratio: a ratio cannot be re-aggregated, and `eventsTotal` is
 * the minimum-sample guard's whole input. A run that scraped three events proves nothing about
 * coverage and must not be allowed to move a baseline.
 */
@Table("import_run_field_stats")
data class ImportRunFieldStatsEntity(
    @Id val id: Long? = null,
    /** Groups the rows one run writes. Minted by the importer; there is no run table to draw from. */
    val runId: UUID,
    val sourceId: Long,
    /** A [TrackedField] key. Renaming one starts a new series and orphans the old baseline. */
    val field: String,
    val eventsTotal: Int,
    val eventsWithValue: Int,
    val observedAt: Instant
)

interface ImportRunFieldStatsRepository : CoroutineCrudRepository<ImportRunFieldStatsEntity, Long> {
    /**
     * The most recent runs for one source and field, newest first.
     *
     * Raw SQL because R2DBC derives no `LIMIT`. Ordered by `observed_at` and then `id`, because two
     * runs of the same source can share a timestamp to the microsecond on a fast machine and an
     * unstable order would make "the previous run" mean different rows on different calls — which is
     * exactly the input the two-consecutive-runs guard reads.
     */
    @Query(
        """
        SELECT * FROM events.import_run_field_stats
        WHERE source_id = :sourceId AND field = :field
        ORDER BY observed_at DESC, id DESC
        LIMIT :limit
        """
    )
    fun findRecent(
        sourceId: Long,
        field: String,
        limit: Int
    ): Flow<ImportRunFieldStatsEntity>
}
