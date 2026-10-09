package de.norm.events.venuecheck

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * A venue the probe checks: no `event_source` points at it and no closure is recorded.
 */
data class ProbeCandidate(
    val venueId: Long,
    val slug: String,
    val websiteUrl: String?,
    val programmeUrl: String?
)

/** A venue on the "needs review" list, with the check that put it there. */
data class NeedsReviewRow(
    val venueId: Long,
    val slug: String,
    val name: String,
    val websiteUrl: String?,
    val programmeUrl: String?,
    val reviewedAt: Instant?,
    val checkedAt: Instant,
    val url: String?,
    val outcome: SiteOutcome,
    val httpStatus: Int?,
    val consecutiveFailures: Int,
    val failingSince: Instant?
)

/**
 * Reads and writes `venue_site_check` (V134), with raw SQL over `venue` and `event_source` rather than calls into
 * those modules (see [VenueCheckModule]). Never writes a `venue` column.
 */
@Repository
class VenueSiteCheckStore(
    private val databaseClient: DatabaseClient
) {
    /** Every venue without an importer and without a recorded closure, by slug. */
    suspend fun findCandidates(): List<ProbeCandidate> =
        databaseClient
            .sql(
                """
                SELECT v.id, v.slug, v.website_url, v.programme_url FROM $EVENTS_SCHEMA.venue v
                WHERE v.closed_on IS NULL
                  AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_source es WHERE es.venue_id = v.id)
                ORDER BY v.slug
                """.trimIndent()
            ).map { row, _ ->
                ProbeCandidate(
                    venueId = row.long("id"),
                    slug = row.string("slug"),
                    websiteUrl = row.get("website_url", String::class.java),
                    programmeUrl = row.get("programme_url", String::class.java)
                )
            }.flow()
            .toList()

    /**
     * Stores [result] for [venueId] and returns the venue's run of failures after it. A failure adds one and
     * keeps the start of the run; [SiteOutcome.OK] ends the run; [SiteOutcome.SKIPPED] leaves it as it was.
     */
    suspend fun record(
        venueId: Long,
        result: ProbeResult,
        checkedAt: Instant
    ): Int {
        val failure = result.outcome.isFailure
        val skipped = result.outcome == SiteOutcome.SKIPPED
        return databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.venue_site_check
                    (venue_id, checked_at, url, outcome, http_status, consecutive_failures, failing_since)
                VALUES (:venueId, :checkedAt, :url, :outcome, :httpStatus,
                        CASE WHEN :failure THEN 1 ELSE 0 END,
                        CASE WHEN :failure THEN :checkedAt END)
                ON CONFLICT (venue_id) DO UPDATE SET
                    checked_at = EXCLUDED.checked_at,
                    url = EXCLUDED.url,
                    outcome = EXCLUDED.outcome,
                    http_status = EXCLUDED.http_status,
                    consecutive_failures = CASE
                        WHEN :failure THEN venue_site_check.consecutive_failures + 1
                        WHEN :skipped THEN venue_site_check.consecutive_failures
                        ELSE 0 END,
                    failing_since = CASE
                        WHEN :failure THEN coalesce(venue_site_check.failing_since, EXCLUDED.checked_at)
                        WHEN :skipped THEN venue_site_check.failing_since
                        END
                RETURNING consecutive_failures
                """.trimIndent()
            ).bind("venueId", venueId)
            .bind("checkedAt", checkedAt)
            .bindNullable("url", result.url, String::class.java)
            .bind("outcome", result.outcome.name)
            .bindNullable("httpStatus", result.httpStatus, Int::class.javaObjectType)
            .bind("failure", failure)
            .bind("skipped", skipped)
            .map { row, _ -> row.get("consecutive_failures", Number::class.java)?.toInt() ?: 0 }
            .one()
            .awaitSingle()
    }

    /**
     * Venues whose run of failures reached [threshold] and that nobody reviewed since the run began, oldest run
     * first. A venue that gained an importer or a closure since leaves the list.
     */
    suspend fun findNeedsReview(threshold: Int): List<NeedsReviewRow> =
        databaseClient
            .sql(
                """
                SELECT v.id, v.slug, v.name, v.website_url, v.programme_url, v.reviewed_at,
                       c.checked_at, c.url, c.outcome, c.http_status, c.consecutive_failures, c.failing_since
                FROM $EVENTS_SCHEMA.venue_site_check c
                JOIN $EVENTS_SCHEMA.venue v ON v.id = c.venue_id
                WHERE c.consecutive_failures >= :threshold
                  AND v.closed_on IS NULL
                  AND (v.reviewed_at IS NULL OR v.reviewed_at < c.failing_since)
                  AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_source es WHERE es.venue_id = v.id)
                ORDER BY c.failing_since, v.slug
                """.trimIndent()
            ).bind("threshold", threshold)
            .map { row, _ ->
                NeedsReviewRow(
                    venueId = row.long("id"),
                    slug = row.string("slug"),
                    name = row.string("name"),
                    websiteUrl = row.get("website_url", String::class.java),
                    programmeUrl = row.get("programme_url", String::class.java),
                    reviewedAt = row.get("reviewed_at", Instant::class.java),
                    checkedAt = requireNotNull(row.get("checked_at", Instant::class.java)),
                    url = row.get("url", String::class.java),
                    outcome = SiteOutcome.valueOf(row.string("outcome")),
                    httpStatus = row.get("http_status", Number::class.java)?.toInt(),
                    consecutiveFailures = row.get("consecutive_failures", Number::class.java)?.toInt() ?: 0,
                    failingSince = row.get("failing_since", Instant::class.java)
                )
            }.flow()
            .toList()

    private fun Readable.long(name: String): Long = requireNotNull(get(name, Number::class.java)).toLong()

    private fun Readable.string(name: String): String = requireNotNull(get(name, String::class.java))

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: Any?,
        type: Class<T>
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
}
