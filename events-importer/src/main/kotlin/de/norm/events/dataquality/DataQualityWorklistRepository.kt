package de.norm.events.dataquality

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
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
        val sourceFilter =
            when (sourceSlug) {
                null -> ""
                MANUAL_BUCKET -> "AND e.event_source_id IS NULL"
                else -> "AND s.slug = :sourceSlug"
            }

        var spec =
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
                           s.slug          AS source_slug
                    FROM $EVENTS_SCHEMA.event e
                    LEFT JOIN $EVENTS_SCHEMA.event_source s ON s.id = e.event_source_id
                    WHERE (${issue.predicate})
                    $sourceFilter
                    ORDER BY e.event_date DESC, e.id DESC
                    LIMIT :limit OFFSET :offset
                    """.trimIndent()
                ).bind("limit", limit)
                .bind("offset", offset)
        if (sourceSlug != null && sourceSlug != MANUAL_BUCKET) {
            spec = spec.bind("sourceSlug", sourceSlug)
        }

        return spec
            .map { row, _ ->
                WorklistRow(
                    id = row.get("id", Number::class.java)!!.toLong(),
                    slug = row.get("slug", String::class.java)!!,
                    title = row.get("title", String::class.java)!!,
                    eventDate = row.get("event_date", LocalDate::class.java)!!,
                    startTime = row.get("start_time", LocalTime::class.java),
                    venueId = row.get("venue_id", Number::class.java)!!.toLong(),
                    sourceSlug = row.get("source_slug", String::class.java) ?: MANUAL_BUCKET
                )
            }.all()
            .asFlow()
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
    val sourceSlug: String
)
