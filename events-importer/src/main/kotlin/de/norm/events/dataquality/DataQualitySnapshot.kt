package de.norm.events.dataquality

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDate

/**
 * One measurement, on one day, for one source — the row that makes a trend possible.
 *
 * **A live report cannot show whether anything moved**, because it only ever describes today. That
 * is the whole reason this table exists: Pillars 3 and 4 are meant to be judged on whether the
 * numbers came down, and without a "before" they are judged on whether they *feel* like they helped.
 *
 * Both `metricCount` and `totalEvents` are stored, never a ratio. A percentage cannot be
 * re-aggregated across sources or days without its denominator, and the denominator is itself a
 * signal — a source whose event count halved has a different story from one whose quality improved.
 */
@Table("data_quality_snapshot")
data class DataQualitySnapshotEntity(
    @Id val id: Long? = null,
    /**
     * The day this describes, not the instant it was written.
     *
     * A writer that runs at 23:59 and one that runs at 00:01 are measuring the same corpus, and a
     * timestamp would make them two different days. The unique constraint is on this.
     */
    val snapshotDate: LocalDate,
    /** Source slug, or `manual`. Denormalised so history survives the source being deleted. */
    val sourceSlug: String,
    /** A [QualityIssue] key, or `totalEvents`. */
    val metric: String,
    val metricCount: Long,
    val totalEvents: Long
    // `created_at` exists on the table and is deliberately NOT mapped here. The daily write updates
    // an existing row when one is present — that is what makes the job safe to re-run, which is how
    // a missed day gets backfilled — and Spring Data writes every mapped property on an UPDATE. A
    // mapped `createdAt` would therefore be set to NULL on the second run of a day, violating the
    // column's NOT NULL and failing the write into the logger's catch, where it would surface as a
    // gap in the series rather than as an error. Leaving it unmapped lets the database default own
    // it, which is what a forensic column should be anyway.
)

interface DataQualitySnapshotRepository : CoroutineCrudRepository<DataQualitySnapshotEntity, Long> {
    /** Everything recorded for one day — used to make the daily write idempotent. */
    fun findBySnapshotDate(snapshotDate: LocalDate): kotlinx.coroutines.flow.Flow<DataQualitySnapshotEntity>
}
