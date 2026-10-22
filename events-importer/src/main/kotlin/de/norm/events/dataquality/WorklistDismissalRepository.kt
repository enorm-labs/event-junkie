package de.norm.events.dataquality

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime

/** One worklist row the operator judged correct as it is, with the event it is about. */
data class WorklistDismissalRow(
    val id: Long,
    val issue: QualityIssue,
    val eventId: Long,
    val eventSlug: String,
    val eventTitle: String,
    val eventDate: LocalDate,
    val sourceSlug: String,
    val dismissedBy: String,
    val dismissedAt: OffsetDateTime,
    /** False once a flagged value changed or the event stopped failing the check: the row is back on the worklist. */
    val active: Boolean
)

/**
 * The `worklist_dismissal` table (ADR-052).
 *
 * Hand-written SQL, because whether a dismissal is still active depends on the issue's own predicate
 * and fingerprint, both SQL fragments from the closed [QualityIssue] enum.
 */
@Repository
class WorklistDismissalRepository(
    private val databaseClient: DatabaseClient
) {
    /** Records a dismissal, or replaces the fingerprint of the one already there. Returns its id. */
    suspend fun upsert(
        issue: QualityIssue,
        eventId: Long,
        fingerprint: String
    ): Long =
        databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.worklist_dismissal (issue, event_id, fingerprint)
                VALUES (:issue, :eventId, :fingerprint)
                ON CONFLICT (issue, event_id) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, dismissed_at = now()
                RETURNING id
                """.trimIndent()
            ).bind("issue", issue.name)
            .bind("eventId", eventId)
            .bind("fingerprint", fingerprint)
            .map { row, _ -> requireNotNull(row.get("id", Number::class.java)).toLong() }
            .one()
            .awaitSingle()

    /** The number of rows deleted: 0 for an unknown id. */
    suspend fun delete(id: Long): Long =
        databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.worklist_dismissal WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** Every dismissal of [issue], the newest first, or only the one with [id]. */
    suspend fun findByIssue(
        issue: QualityIssue,
        id: Long? = null
    ): List<WorklistDismissalRow> {
        val fingerprint = requireNotNull(issue.fingerprint) { "${issue.key} has no fingerprint" }
        return databaseClient
            .sql(
                """
                SELECT d.id, d.event_id, d.dismissed_by, d.dismissed_at,
                       e.slug, e.title, e.event_date, s.slug AS source_slug,
                       COALESCE((${issue.predicate}) AND d.fingerprint = md5(($fingerprint)::text), FALSE) AS active
                FROM $EVENTS_SCHEMA.worklist_dismissal d
                JOIN $EVENTS_SCHEMA.event e ON e.id = d.event_id
                LEFT JOIN $EVENTS_SCHEMA.event_source s ON s.id = e.event_source_id
                WHERE d.issue = :issue ${if (id != null) "AND d.id = :id" else ""}
                ORDER BY d.dismissed_at DESC, d.id DESC
                """.trimIndent()
            ).bind("issue", issue.name)
            .let { spec -> if (id != null) spec.bind("id", id) else spec }
            .map { row, _ ->
                WorklistDismissalRow(
                    id = requireNotNull(row.get("id", Number::class.java)).toLong(),
                    issue = issue,
                    eventId = requireNotNull(row.get("event_id", Number::class.java)).toLong(),
                    eventSlug = requireNotNull(row.get("slug", String::class.java)),
                    eventTitle = requireNotNull(row.get("title", String::class.java)),
                    eventDate = requireNotNull(row.get("event_date", LocalDate::class.java)),
                    sourceSlug = row.get("source_slug", String::class.java) ?: DataQualityWorklistRepository.MANUAL_BUCKET,
                    dismissedBy = requireNotNull(row.get("dismissed_by", String::class.java)),
                    dismissedAt = requireNotNull(row.get("dismissed_at", OffsetDateTime::class.java)),
                    active = row.get("active", Boolean::class.javaObjectType) == true
                )
            }.flow()
            .toList()
    }

    /** Deletes the dismissals of events that ended before [today]. Returns how many. */
    suspend fun deleteForEventsBefore(today: LocalDate): Long =
        databaseClient
            .sql(
                "DELETE FROM $EVENTS_SCHEMA.worklist_dismissal d USING $EVENTS_SCHEMA.event e " +
                    "WHERE e.id = d.event_id AND COALESCE(e.end_date, e.event_date) < :today"
            ).bind("today", today)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
}
