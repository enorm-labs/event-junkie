package de.norm.events.dataquality

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalTime

/**
 * The other half of the report: **which** events fail a metric, not how many.
 *
 * A count tells a steward the size of the problem and gives them nowhere to start. This is the
 * surface that closes the loop — they page through it and fix each event through the existing
 * `PUT /api/admin/events/{id}`, which is why Pillar 1 needs no frontend to be useful.
 *
 * Hand-written rather than derived, for the same reason [DataQualityRepository] is: the predicates
 * involve `NOT EXISTS` over join tables and vary per metric, and neither has a derived form. The
 * predicate itself comes from [QualityIssue] — a closed enum — so nothing a caller controls is
 * concatenated into SQL.
 *
 * **It returns a lean projection rather than the full `EventResponse`, and the deviation from the
 * plan is deliberate.** Assembling an `EventResponse` resolves artists, promoters and genre tags per
 * event — three extra round-trips to attach associations to events that were selected *because they
 * lack them*. `concertsWithoutArtist` would return an empty artist list on every single row. What a
 * steward needs to decide whether to open something is what is here: when it is, where it is, and
 * what it is called.
 */
@Repository
class DataQualityWorklistRepository(
    private val template: R2dbcEntityTemplate
) {
    /**
     * The offending events for one issue, newest-first by date.
     *
     * `sourceSlug` is matched through `event_source` rather than carried on `event`, and `manual`
     * selects the events with no source at all — the same synthetic bucket the report uses, so the
     * two agree about what they are counting.
     *
     * Ordered by `event_date` then `id`: a page boundary that falls between two events on the same
     * date must not shuffle on the next request, and `event_date` alone is not unique.
     */
    fun findOffenders(
        issue: QualityIssue,
        sourceSlug: String?,
        limit: Int,
        offset: Int
    ): Flow<WorklistRow> {
        val fingerprint = issue.fingerprint?.let { "md5(($it)::text)" } ?: "NULL"
        val spec =
            template
                .databaseClient
                .sql(
                    """
                    SELECT e.id            AS id,
                           e.slug          AS slug,
                           e.title         AS title,
                           e.event_date    AS event_date,
                           e.start_time    AS start_time,
                           e.venue_id      AS venue_id,
                           s.slug          AS source_slug,
                           $fingerprint    AS fingerprint
                    FROM $EVENTS_SCHEMA.event e
                    LEFT JOIN $EVENTS_SCHEMA.event_source s ON s.id = e.event_source_id
                    WHERE (${issue.predicate})
                    ${sourceFilter(sourceSlug)}
                    AND NOT ${dismissed(issue)}
                    ORDER BY e.event_date DESC, e.id DESC
                    LIMIT :limit OFFSET :offset
                    """.trimIndent()
                ).bind("limit", limit)
                .bind("offset", offset)
                .bindIssueAndSource(issue, sourceSlug)

        return spec
            .map { row, _ ->
                WorklistRow(
                    id = row.required("id", Number::class.java).toLong(),
                    slug = row.required("slug", String::class.java),
                    title = row.required("title", String::class.java),
                    eventDate = row.required("event_date", LocalDate::class.java),
                    startTime = row.get("start_time", LocalTime::class.java),
                    venueId = row.required("venue_id", Number::class.java).toLong(),
                    sourceSlug = row.get("source_slug", String::class.java) ?: MANUAL_BUCKET,
                    fingerprint = row.get("fingerprint", String::class.java)
                )
            }.all()
            .asFlow()
    }

    /** The offenders a still-matching dismissal leaves out, under the same filter as [findOffenders]. */
    suspend fun countDismissed(
        issue: QualityIssue,
        sourceSlug: String?
    ): Long {
        if (issue.fingerprint == null) return 0
        return template
            .databaseClient
            .sql(
                """
                SELECT COUNT(*) AS dismissed
                FROM $EVENTS_SCHEMA.event e
                LEFT JOIN $EVENTS_SCHEMA.event_source s ON s.id = e.event_source_id
                WHERE (${issue.predicate})
                ${sourceFilter(sourceSlug)}
                AND ${dismissed(issue)}
                """.trimIndent()
            ).bindIssueAndSource(issue, sourceSlug)
            .map { row, _ -> row.required("dismissed", Number::class.java).toLong() }
            .awaitSingle()
    }

    /**
     * The fingerprint of one event as [issue] judges it now, or `null` when the event does not fail it.
     * A dismissal stores this value, never one a client sends.
     */
    suspend fun currentFingerprint(
        issue: QualityIssue,
        eventId: Long
    ): String? {
        val fingerprint = requireNotNull(issue.fingerprint) { "${issue.key} has no fingerprint" }
        return template
            .databaseClient
            .sql("SELECT md5(($fingerprint)::text) AS fingerprint FROM $EVENTS_SCHEMA.event e WHERE e.id = :eventId AND (${issue.predicate})")
            .bind("eventId", eventId)
            .map { row, _ -> row.required("fingerprint", String::class.java) }
            .awaitSingleOrNull()
    }

    private fun sourceFilter(sourceSlug: String?): String =
        when (sourceSlug) {
            null -> ""
            MANUAL_BUCKET -> "AND e.event_source_id IS NULL"
            else -> "AND s.slug = :sourceSlug"
        }

    /** True while a dismissal of [issue] still matches the event's flagged values. Always false for an issue with no fingerprint. */
    private fun dismissed(issue: QualityIssue): String =
        issue.fingerprint?.let {
            "EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.worklist_dismissal d " +
                "WHERE d.issue = :issue AND d.event_id = e.id AND d.fingerprint = md5(($it)::text))"
        } ?: "FALSE"

    private fun DatabaseClient.GenericExecuteSpec.bindIssueAndSource(
        issue: QualityIssue,
        sourceSlug: String?
    ): DatabaseClient.GenericExecuteSpec {
        val bound = if (issue.fingerprint != null) bind("issue", issue.name) else this
        return if (sourceSlug != null && sourceSlug != MANUAL_BUCKET) bound.bind("sourceSlug", sourceSlug) else bound
    }

    companion object {
        /**
         * Events created by hand through the admin API have no `event_source_id`. They get a named
         * bucket rather than being filtered out, so the report's total agrees with the table's.
         */
        const val MANUAL_BUCKET = "manual"
    }
}

/** One event failing one metric — see [DataQualityWorklistRepository.findOffenders]. */
data class WorklistRow(
    val id: Long,
    val slug: String,
    val title: String,
    val eventDate: LocalDate,
    val startTime: LocalTime?,
    val venueId: Long,
    val sourceSlug: String,
    /** Null for an issue no person can dismiss. */
    val fingerprint: String?
)

/**
 * Reads a column the projection declares NOT NULL, naming it when it is absent.
 *
 * The columns come from a hand-written `SELECT`, so a null means the projection and this mapping
 * have drifted apart. Saying which column drifted beats the bare `NullPointerException` that `!!`
 * would raise here.
 */
private fun <T : Any> Readable.required(
    column: String,
    type: Class<T>
): T = requireNotNull(get(column, type)) { "Column '$column' is missing from the data-quality worklist projection" }
